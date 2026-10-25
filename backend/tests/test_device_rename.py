"""PATCH /devices/{id}: rename one of the caller's own devices."""

import uuid

import pytest
from sqlalchemy import select

from app.models import AuditEvent
from tests.conftest import Factory


def audit_rows(app, action):
    with app.state.session_factory() as db:
        return list(db.scalars(select(AuditEvent).where(AuditEvent.action == action).order_by(AuditEvent.id)))


def test_rename_the_current_device(alice):
    r = alice.patch(f"/devices/{alice.device_id}", {"name": "  Kitchen tablet  "})
    assert r.status_code == 200, r.text
    assert r.json()["id"] == alice.device_id and r.json()["name"] == "Kitchen tablet"
    assert alice.get("/devices/me").json()["name"] == "Kitchen tablet"


def test_rename_another_own_device(factory, alice):
    second = factory.login(alice, "tablet")
    r = alice.patch(f"/devices/{second.device_id}", {"name": "Old tablet"})
    assert r.status_code == 200 and r.json()["name"] == "Old tablet"
    assert second.get("/devices/me").json()["name"] == "Old tablet"
    names = {d["id"]: d["name"] for d in alice.get(f"/users/{alice.id}/devices").json()}
    assert names[second.device_id] == "Old tablet"


def test_other_users_and_revoked_devices_are_not_found(factory, alice, bob):
    r = alice.patch(f"/devices/{bob.device_id}", {"name": "mine now"})
    assert r.status_code == 404
    assert bob.get("/devices/me").json()["name"] != "mine now"
    second = factory.login(alice, "tablet")
    assert second.delete(f"/devices/{second.device_id}").status_code == 204
    assert alice.patch(f"/devices/{second.device_id}", {"name": "x"}).status_code == 404
    assert alice.patch(f"/devices/{uuid.uuid4()}", {"name": "x"}).status_code == 404


@pytest.mark.parametrize("name", ["", "   ", "x" * 65])
def test_invalid_names_are_rejected(alice, name):
    before = alice.get("/devices/me").json()["name"]
    assert alice.patch(f"/devices/{alice.device_id}", {"name": name}).status_code == 422
    assert alice.get("/devices/me").json()["name"] == before
    assert alice.patch(f"/devices/{alice.device_id}", {}).status_code == 422


def test_rename_emits_devices_changed_to_members_and_is_audited(factory, alice, bob, carol):
    factory.direct(alice, bob)
    second = factory.login(alice, "tablet")
    cursor_bob, cursor_carol, cursor_second = (
        bob.sync()["next_cursor"],
        carol.sync()["next_cursor"],
        second.sync()["next_cursor"],
    )
    assert alice.patch(f"/devices/{alice.device_id}", {"name": "Renamed"}).status_code == 200
    assert [e["data"] for e in bob.events("devices_changed", after=cursor_bob)] == [{"user_id": alice.id}]
    assert [e["data"] for e in second.events("devices_changed", after=cursor_second)] == [{"user_id": alice.id}]
    assert carol.events("devices_changed", after=cursor_carol) == []  # shares no conversation
    (row,) = audit_rows(alice.app, "device_renamed")
    assert (row.actor_user_id, row.target_user_id, row.target_id) == (alice.id, alice.id, alice.device_id)
    assert "Renamed" not in str(row.details) and "Renamed" not in str(row.target_id)


def test_renaming_to_the_same_name_changes_nothing(factory, alice, bob):
    factory.direct(alice, bob)
    alice.patch(f"/devices/{alice.device_id}", {"name": "Same"})
    cursor = bob.sync()["next_cursor"]
    r = alice.patch(f"/devices/{alice.device_id}", {"name": " Same "})
    assert r.status_code == 200 and r.json()["name"] == "Same"
    assert bob.events("devices_changed", after=cursor) == []
    assert len(audit_rows(alice.app, "device_renamed")) == 1


def test_renames_are_rate_limited_per_user(make_app):
    app, client = make_app(rate_limit_enabled=True)
    f = Factory(app, client)
    a = f.user("alice")
    codes = [a.patch(f"/devices/{a.device_id}", {"name": f"n{i}"}).status_code for i in range(21)]
    assert codes[19] == 200 and codes[20] == 429

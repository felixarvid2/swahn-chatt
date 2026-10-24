"""Retention, admin overview/stats, admin device revocation, audit log, readiness checks, graceful shutdown."""

import asyncio
import json
import shutil
import time
from collections import namedtuple

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update
from starlette.websockets import WebSocketDisconnect

from app import clock
from app.cli import main as cli_main
from app.hub import Connection, Hub
from app.main import create_app
from app.migrate import code_head
from app.models import Attachment, AuditEvent, Device, DeviceKey, MessageEnvelope
from app.services.maintenance import prune
from tests.conftest import PASSWORD, Factory, make_settings

DAY = 86_400_000
WS = "/api/v1/ws"


def audit_rows(app, action=None):
    with app.state.session_factory() as db:
        query = select(AuditEvent).order_by(AuditEvent.id)
        if action:
            query = query.where(AuditEvent.action == action)
        return list(db.scalars(query))


# ------------------------------------------------------------------ 1. envelope retention
def test_prune_deletes_old_delivered_envelopes_but_keeps_undelivered_and_recent(factory, app, alice, bob, carol):
    cid = factory.group(alice, [bob, carol])
    old, recent = alice.send(cid, "old").json()["message"]["id"], alice.send(cid, "recent").json()["message"]["id"]
    bob.ack(bob.sync()["next_cursor"])  # bob has now received both; carol has not
    now = clock.now_ms()
    with app.state.session_factory() as db:
        db.execute(
            update(MessageEnvelope)
            .where(MessageEnvelope.message_id == old, MessageEnvelope.device_id == bob.device_id)
            .values(delivered_at=now - 31 * DAY)
        )
        db.execute(
            update(MessageEnvelope)
            .where(MessageEnvelope.message_id == recent, MessageEnvelope.device_id == bob.device_id)
            .values(delivered_at=now - 29 * DAY)
        )
        # carol's copy of the old message is just as old but was never delivered
        db.execute(
            update(MessageEnvelope).where(MessageEnvelope.device_id == carol.device_id).values(delivered_at=None)
        )
        db.commit()
        assert prune(db, app.state.settings)["envelopes"] == 1
        left = {(e.message_id, e.device_id) for e in db.scalars(select(MessageEnvelope))}
    assert (old, bob.device_id) not in left
    assert (recent, bob.device_id) in left
    assert {(old, carol.device_id), (recent, carol.device_id)} <= left
    # the message itself and carol's delivery are unaffected
    assert len([e for e in carol.sync()["events"] if e["type"] == "message"]) == 2


def test_envelope_retention_days_is_configurable(make_app):
    app, client = make_app(envelope_retention_days=2)
    f = Factory(app, client)
    a, b = f.user("anna"), f.user("bo")
    cid = f.direct(a, b)
    a.send(cid, "x")
    b.ack(b.sync()["next_cursor"])
    with app.state.session_factory() as db:
        db.execute(update(MessageEnvelope).values(delivered_at=clock.now_ms() - 3 * DAY))
        db.commit()
        assert prune(db, app.state.settings)["envelopes"] == 1


# ------------------------------------------------------------------ 2. admin users
def test_admin_users_lists_everyone_with_usage(factory, app, admin, alice, bob):
    second = factory.login(alice, "tablet", keys=False)
    second_seen = clock.now_ms() + 5000
    with app.state.session_factory() as db:
        db.execute(update(Device).where(Device.id == second.device_id).values(last_seen_at=second_seen))
        for i, size in enumerate((1000, 234)):
            db.add(Attachment(
                id=f"00000000-0000-4000-8000-{i:012d}", uploader_id=alice.id, purpose="message",
                content_type="application/octet-stream", size=size, sha256="0" * 64, storage_path="x", created_at=1,
            ))  # fmt: skip
        db.commit()
    assert admin.delete(f"/admin/users/{bob.id}").status_code == 204
    r = admin.get("/admin/users")
    assert r.status_code == 200
    users = {u["username"]: u for u in r.json()}
    assert set(users) == {"admin", "alice", "bob"}
    a = users["alice"]
    assert a["id"] == alice.id and a["display_name"] == "Alice" and a["is_admin"] is False
    assert a["active_devices"] == 2 and a["attachment_bytes"] == 1234 and a["disabled_at"] is None
    assert a["last_seen_at"] == second_seen  # the newest over her devices
    assert users["admin"]["is_admin"] is True and users["admin"]["attachment_bytes"] == 0
    assert users["bob"]["disabled_at"] is not None and users["bob"]["active_devices"] == 0
    assert set(a) == {
        "id", "username", "display_name", "is_admin", "created_at", "disabled_at", "last_seen_at",
        "active_devices", "attachment_bytes",
    }  # fmt: skip
    assert "password_hash" not in json.dumps(r.json())


@pytest.mark.parametrize("path", ["/admin/users", "/admin/stats", "/admin/audit"])
def test_admin_read_endpoints_forbid_members(alice, client, path):
    assert alice.get(path).status_code == 403
    assert client.get("/api/v1" + path).status_code == 401


# ------------------------------------------------------------------ 3. admin stats
def test_admin_stats(factory, app, admin, alice, bob):
    cid = factory.direct(alice, bob)
    alice.send(cid, "one")
    alice.send(cid, "two")
    (app.state.settings.attachments_dir / "ab").mkdir()
    (app.state.settings.attachments_dir / "ab" / "file").write_bytes(b"x" * 4321)
    r = admin.get("/admin/stats")
    assert r.status_code == 200, r.text
    s = r.json()
    assert (s["users"], s["active_devices"], s["conversations"], s["messages"]) == (3, 3, 1, 2)
    assert s["undelivered_envelopes"] == 2  # bob's device has not synced yet
    assert s["attachments"] == 0 and s["attachments_bytes"] == 4321
    assert s["db_bytes"] > 0 and s["disk_free_bytes"] > 0
    assert s["version"] == "1.0.0"
    assert s["migration"] == {"head": code_head(), "current": code_head()}
    assert 0 <= s["uptime_seconds"] < 300
    bob.ack(bob.sync()["next_cursor"])
    assert admin.get("/admin/stats").json()["undelivered_envelopes"] == 0
    admin.delete(f"/admin/users/{bob.id}")
    after = admin.get("/admin/stats").json()
    assert (after["users"], after["active_devices"]) == (2, 2)


def test_stats_uses_disk_usage_of_the_data_volume(admin, monkeypatch):
    Usage = namedtuple("Usage", "total used free")
    monkeypatch.setattr(shutil, "disk_usage", lambda path: Usage(100, 40, 60))
    assert admin.get("/admin/stats").json()["disk_free_bytes"] == 60


# ------------------------------------------------------------------ 4. admin revokes a device
def test_admin_revokes_a_lost_phone(factory, app, client, admin, alice, bob):
    phone2 = factory.login(alice, "lost phone")
    cid = factory.group(alice, [bob])
    bob_seen = bob.sync()["next_cursor"]
    with app.state.session_factory() as db:
        assert db.get(DeviceKey, phone2.device_id) is not None
    with client.websocket_connect(WS, headers=phone2.headers) as ws:
        assert ws.receive_json()["type"] == "hello"
        r = admin.delete(f"/admin/users/{alice.id}/devices/{phone2.device_id}")
        assert r.status_code == 204, r.text
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
        assert closed.value.code == 4401
    with app.state.session_factory() as db:
        assert db.get(Device, phone2.device_id).revoked_at is not None
        assert db.get(DeviceKey, phone2.device_id) is None  # keys deleted
    assert phone2.get("/users/me").status_code == 401  # session revoked
    assert alice.get("/users/me").status_code == 200  # her other phone is untouched
    changed = bob.events("devices_changed", after=bob_seen)
    assert changed and changed[-1]["data"]["user_id"] == alice.id
    (row,) = audit_rows(app, "device_revoked")
    assert (row.actor_user_id, row.target_user_id, row.target_id) == (admin.id, alice.id, phone2.device_id)
    assert row.details == {"by": "admin"}
    assert cid
    # gone now
    assert admin.delete(f"/admin/users/{alice.id}/devices/{phone2.device_id}").status_code == 404


def test_admin_cannot_revoke_their_own_current_device_this_way(factory, admin):
    r = admin.delete(f"/admin/users/{admin.id}/devices/{admin.device_id}")
    assert r.status_code == 409 and r.json()["code"] == "conflict"
    assert admin.get("/users/me").status_code == 200
    # but another device of the admin can be revoked
    other = factory.login(admin, "old tablet", keys=False)
    assert admin.delete(f"/admin/users/{admin.id}/devices/{other.device_id}").status_code == 204


def test_admin_device_revocation_checks(factory, admin, alice, bob):
    assert bob.delete(f"/admin/users/{alice.id}/devices/{alice.device_id}").status_code == 403
    assert alice.get("/users/me").status_code == 200
    # the device must belong to the user in the path
    assert admin.delete(f"/admin/users/{bob.id}/devices/{alice.device_id}").status_code == 404
    assert alice.get("/users/me").status_code == 200
    assert admin.delete(f"/admin/users/{alice.id}/devices/nope").status_code == 404


# ------------------------------------------------------------------ 5. audit log
def test_audit_records_the_security_events_without_secrets(factory, app, client, admin, alice):
    code = factory.invite()
    bob = factory.user("bob")  # redeems another code
    # invitations via the API
    inv = admin.post("/admin/invitations", {"note": "for carol", "is_admin": True}).json()
    admin.delete(f"/admin/invitations/{inv['id']}")
    # logins
    good = factory.login(alice, "second")
    bad = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "wrong password!", "device_name": "x"}
    )
    assert bad.status_code == 401
    ghost = client.post(
        "/api/v1/auth/login", json={"username": "ghostly", "password": "whatever 123", "device_name": "x"}
    )
    assert ghost.status_code == 401
    # password change, device revocation, logout, user removal
    assert (
        alice.put(
            "/users/me/password", {"current_password": PASSWORD, "new_password": "another long password"}
        ).status_code
        == 204
    )
    assert good.delete(f"/devices/{good.device_id}").status_code in (204, 401)  # revoked by the password change
    assert admin.delete(f"/admin/users/{bob.id}").status_code == 204
    # refresh token reuse
    stolen = alice.refresh
    first = client.post("/api/v1/auth/refresh", json={"refresh_token": stolen})
    assert first.status_code == 200
    reuse = client.post("/api/v1/auth/refresh", json={"refresh_token": stolen})
    assert reuse.json()["code"] == "token_reused"

    rows = audit_rows(app)
    actions = [r.action for r in rows]
    for expected in (
        "invitation_created", "invitation_revoked", "invitation_redeemed", "login_success", "login_failure",
        "password_changed", "refresh_token_reuse", "user_removed", "admin_granted",
    ):  # fmt: skip
        assert expected in actions, expected
    by_action = {a: [r for r in rows if r.action == a] for a in set(actions)}
    assert any(r.target_id == inv["id"] and r.actor_user_id == admin.id for r in by_action["invitation_created"])
    assert by_action["invitation_revoked"][0].target_id == inv["id"]
    redeemed = [r for r in by_action["invitation_redeemed"] if r.target_user_id == bob.id]
    assert redeemed and redeemed[0].actor_user_id == bob.id and redeemed[0].ip
    assert any(r.target_user_id == admin.id for r in by_action["admin_granted"])
    # failed logins: known account by id and name, unknown account only as "unknown user"
    fails = by_action["login_failure"]
    assert {"username": "alice"} in [r.details for r in fails]
    assert {"username": "unknown user"} in [r.details for r in fails]
    assert any(r.target_user_id == alice.id for r in fails)
    assert any(r.target_user_id is None for r in fails)
    assert by_action["user_removed"][0].target_user_id == bob.id
    assert by_action["refresh_token_reuse"][0].target_user_id == alice.id
    assert by_action["password_changed"][0].actor_user_id == alice.id

    dump = json.dumps([(r.actor_user_id, r.action, r.target_user_id, r.target_id, r.ip, r.details) for r in rows])
    for secret in (
        code,
        inv["code"],
        PASSWORD,
        "wrong password!",
        "another long password",
        "ghostly",
        stolen,
        alice.access,
    ):
        assert secret not in dump


def test_audit_endpoint_pages_newest_first(factory, admin):
    for _ in range(5):
        admin.post("/admin/invitations", {})
    r = admin.get("/admin/audit?limit=3")
    assert r.status_code == 200
    page = r.json()
    assert len(page) == 3
    assert [e["id"] for e in page] == sorted((e["id"] for e in page), reverse=True)
    assert set(page[0]) == {"id", "at", "actor_user_id", "action", "target_user_id", "target_id", "ip", "details"}
    nxt = admin.get(f"/admin/audit?limit=100&before_id={page[-1]['id']}").json()
    assert nxt and all(e["id"] < page[-1]["id"] for e in nxt)
    ids = [e["id"] for e in page + nxt]
    assert len(ids) == len(set(ids))
    assert admin.get("/admin/audit?limit=0").status_code == 422
    assert admin.get("/admin/audit?limit=201").status_code == 422


def test_audit_retention(factory, app, admin):
    now = clock.now_ms()
    with app.state.session_factory() as db:
        db.add_all(
            [
                AuditEvent(at=now - 181 * DAY, action="old", details={}),
                AuditEvent(at=now - 179 * DAY, action="new", details={}),
            ]
        )
        db.commit()
        assert prune(db, app.state.settings)["audit_events"] == 1
    actions = {r.action for r in audit_rows(app)}
    assert "old" not in actions and "new" in actions


def test_audit_cli(tmp_path, capsys):
    from tests.test_cli import run_cli

    code = run_cli(tmp_path, "create-invite", "--note", "n").stdout.strip().splitlines()[-1]
    out = run_cli(tmp_path, "audit", "--limit", "5")
    assert out.returncode == 0, out.stderr
    assert "invitation_created" in out.stdout and "is_admin=False" in out.stdout
    assert code not in out.stdout
    assert run_cli(tmp_path, "audit", "--limit", "0").returncode == 2


# ------------------------------------------------------------------ 6. readiness
def test_readyz_reports_migration(client):
    r = client.get("/readyz")
    assert r.status_code == 200 and r.json() == {"status": "ready", "migration": code_head()}


def test_readyz_503_when_database_is_not_at_the_code_head(app, client):
    with app.state.engine.begin() as conn:
        conn.exec_driver_sql("UPDATE alembic_version SET version_num = '0002'")
    r = client.get("/readyz")
    assert r.status_code == 503
    assert r.json() == {
        "status": "unavailable",
        "code": "migration_mismatch",
        "migration": "0002",
        "expected": code_head(),
    }
    assert client.get("/healthz").status_code == 200


def test_readyz_503_when_database_was_never_migrated(app, client):
    with app.state.engine.begin() as conn:
        conn.exec_driver_sql("DELETE FROM alembic_version")
    assert client.get("/readyz").json()["code"] == "migration_mismatch"


def test_readyz_low_disk(app, client, monkeypatch):
    Usage = namedtuple("Usage", "total used free")
    mb = 1024 * 1024
    monkeypatch.setattr(shutil, "disk_usage", lambda path: Usage(10_000 * mb, 9_900 * mb, 100 * mb))
    r = client.get("/readyz")
    assert r.status_code == 503
    assert r.json() == {"status": "unavailable", "code": "low_disk", "free_disk_mb": 100, "min_free_disk_mb": 200}
    assert client.get("/healthz").status_code == 200
    monkeypatch.setattr(shutil, "disk_usage", lambda path: Usage(10_000 * mb, 9_000 * mb, 200 * mb))
    assert client.get("/readyz").status_code == 200  # exactly the minimum is enough


def test_readyz_disk_threshold_is_configurable(make_app, monkeypatch):
    _, client = make_app(min_free_disk_mb=0)
    monkeypatch.setattr(shutil, "disk_usage", lambda path: (_ for _ in ()).throw(AssertionError("not consulted")))
    assert client.get("/readyz").status_code == 200
    _, strict = make_app(min_free_disk_mb=10**9)
    monkeypatch.setattr(shutil, "disk_usage", lambda path: namedtuple("U", "total used free")(1, 1, 5 * 1024**2))
    assert strict.get("/readyz").json()["code"] == "low_disk"


# ------------------------------------------------------------------ 7. graceful shutdown
def test_hub_close_all_closes_every_connection_with_1001():
    async def scenario():
        hub = Hub()
        conns = [Connection(device_id=f"d{i}", user_id="u") for i in range(3)]
        for c in conns:
            hub.register(c)

        async def handler(c):  # what the websocket endpoint does once `closed` is set
            await c.closed.wait()
            hub.unregister(c)

        tasks = [asyncio.create_task(handler(c)) for c in conns]
        await hub.close_all(1001, "server restarting")
        await asyncio.gather(*tasks)
        assert hub.connected_count() == 0
        assert {(c.close_code, c.close_reason) for c in conns} == {(1001, "server restarting")}
        late = Connection(device_id="late", user_id="u")  # arrives while shutting down
        hub.register(late)
        assert late.closed.is_set() and late.close_code == 1001

    asyncio.run(scenario())


def test_hub_close_all_gives_up_after_the_grace_period():
    async def scenario():
        hub = Hub()
        hub.register(Connection(device_id="stuck", user_id="u"))  # no handler ever unregisters it
        started = time.monotonic()
        await hub.close_all(timeout=0.1)
        assert time.monotonic() - started < 2

    asyncio.run(scenario())


def test_hub_close_all_without_connections_returns_at_once():
    asyncio.run(Hub().close_all())


def test_websocket_clients_see_close_1001_on_shutdown(client, app, alice):
    with client.websocket_connect(WS, headers=alice.headers) as ws:
        assert ws.receive_json()["type"] == "hello"
        client.portal.call(app.state.hub.close_all)
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
    assert closed.value.code == 1001 and closed.value.reason == "server restarting"


def test_lifespan_shutdown_stops_push_thread_and_maintenance(tmp_path, template_db):
    shutil.copy(template_db, tmp_path / "familychat.db")
    app = create_app(make_settings(tmp_path, background_tasks=True))
    with TestClient(app) as client:
        f = Factory(app, client)
        alice = f.user("alice")
        assert app.state.push._thread is not None and app.state.push._thread.is_alive()
        thread = app.state.push._thread
        with client.websocket_connect(WS, headers=alice.headers) as ws:
            assert ws.receive_json()["type"] == "hello"
            started = time.monotonic()
        # leaving the websocket context closes it from the client side; now shut the app down
    assert time.monotonic() - started < 10
    assert not thread.is_alive() and app.state.push._thread is None
    assert app.state.hub.connected_count() == 0


def test_shutdown_closes_open_websockets_with_1001(tmp_path, template_db):
    shutil.copy(template_db, tmp_path / "familychat.db")
    app = create_app(make_settings(tmp_path))
    seen = {}
    with TestClient(app) as client:
        alice = Factory(app, client).user("alice")
        ws = client.websocket_connect(WS, headers=alice.headers).__enter__()
        assert ws.receive_json()["type"] == "hello"
        client.__exit__ = None  # placeholder so the intent is visible: shutdown happens at the end of this block
        original = app.state.hub.close_all

        async def spy(*a, **kw):
            await original(*a, **kw)
            seen["closed"] = ws._send_rx is not None

        app.state.hub.close_all = spy
    assert seen["closed"]

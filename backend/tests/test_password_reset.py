"""Admin issued password reset: single use, 24 h, stored hashed, ends every old session and device."""

import re

from sqlalchemy import select

from app import clock
from app.models import AuditEvent, Device, DeviceKey, PasswordReset
from tests.conftest import PASSWORD

CODE_RE = re.compile(r"^[A-Z2-9]{5}(-[A-Z2-9]{5}){3}$")
NEW_PASSWORD = "a brand new password"
HOUR = 3_600_000


def issue(admin, user) -> dict:
    r = admin.post(f"/admin/users/{user.id}/password-reset")
    assert r.status_code == 201, r.text
    return r.json()


def reset(client, code, password=NEW_PASSWORD, device="New phone"):
    return client.post(
        "/api/v1/auth/password/reset", json={"code": code, "new_password": password, "device_name": device}
    )


def login(client, username, password):
    return client.post("/api/v1/auth/login", json={"username": username, "password": password, "device_name": "again"})


def test_issue_returns_code_and_24h_expiry(admin, alice, app):
    before = clock.now_ms()
    out = issue(admin, alice)
    assert set(out) == {"code", "expires_at"} and CODE_RE.match(out["code"])
    assert before + 24 * HOUR <= out["expires_at"] <= clock.now_ms() + 24 * HOUR
    with app.state.session_factory() as db:
        (row,) = db.scalars(select(PasswordReset)).all()
        assert row.user_id == alice.id and row.created_by == admin.id
        assert out["code"] not in row.code_hash and out["code"].replace("-", "") not in row.code_hash
        assert len(row.code_hash) == 64


def test_only_admins_may_issue_and_unknown_or_removed_users_are_404(admin, alice, bob, factory):
    assert alice.post(f"/admin/users/{bob.id}/password-reset").status_code == 403
    assert admin.post("/admin/users/00000000-0000-4000-8000-000000000000/password-reset").status_code == 404
    assert admin.delete(f"/admin/users/{bob.id}").status_code == 204
    assert admin.post(f"/admin/users/{bob.id}/password-reset").status_code == 404


def test_reset_sets_the_password_and_returns_a_fresh_session(admin, alice, client):
    code = issue(admin, alice)["code"]
    r = reset(client, code)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["user"]["id"] == alice.id
    assert body["device"]["id"] != alice.device_id and body["device"]["name"] == "New phone"
    me = client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {body['access_token']}"})
    assert me.status_code == 200 and me.json()["id"] == alice.id
    assert login(client, "alice", NEW_PASSWORD).status_code == 200
    assert login(client, "alice", PASSWORD).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": body["refresh_token"]}).status_code == 200


def test_code_is_single_use(admin, alice, client):
    code = issue(admin, alice)["code"]
    assert reset(client, code).status_code == 200
    again = reset(client, code, password="yet another password")
    assert again.status_code == 404 and again.json()["code"] == "invalid_reset_code"
    assert login(client, "alice", NEW_PASSWORD).status_code == 200  # the second attempt changed nothing


def test_unknown_and_malformed_codes(client, alice):
    assert reset(client, "AAAAA-BBBBB-CCCCC-DDDDD").status_code == 404
    assert reset(client, "x", password="short").status_code == 422
    assert client.post("/api/v1/auth/password/reset", json={"code": "x"}).status_code == 422


def test_expired_code_is_refused(admin, alice, client, app):
    code = issue(admin, alice)["code"]
    with app.state.session_factory() as db:
        row = db.scalars(select(PasswordReset)).one()
        row.expires_at = clock.now_ms() - 1
        db.commit()
    r = reset(client, code)
    assert r.status_code == 404 and r.json()["code"] == "invalid_reset_code"
    assert login(client, "alice", PASSWORD).status_code == 200


def test_a_new_code_replaces_the_old_one(admin, alice, client):
    first = issue(admin, alice)["code"]
    second = issue(admin, alice)["code"]
    assert reset(client, first).status_code == 404
    assert reset(client, second).status_code == 200


def test_an_invitation_code_is_not_a_reset_code_and_vice_versa(admin, alice, client, factory):
    assert reset(client, factory.invite()).status_code == 404
    code = issue(admin, alice)["code"]
    r = client.post(
        "/api/v1/auth/invite/redeem",
        json={"code": code, "username": "mallory", "password": PASSWORD, "display_name": "M", "device_name": "d"},
    )
    assert r.status_code == 404


def test_removed_account_cannot_be_reset(admin, alice, client):
    code = issue(admin, alice)["code"]
    assert admin.delete(f"/admin/users/{alice.id}").status_code == 204
    assert reset(client, code).status_code == 404


def test_all_old_sessions_and_devices_die_and_their_keys_are_deleted(admin, alice, bob, factory, client, app):
    alice2 = factory.login(alice, "tablet")
    cid = factory.direct(alice, bob)
    code = issue(admin, alice)["code"]
    new = reset(client, code).json()
    for dead in (alice, alice2):
        assert dead.get("/users/me").status_code == 401
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": dead.refresh}).status_code == 401
    with app.state.session_factory() as db:
        old = db.scalars(select(Device).where(Device.id.in_([alice.device_id, alice2.device_id]))).all()
        assert len(old) == 2 and all(d.revoked_at is not None for d in old)
        assert (
            db.scalars(select(DeviceKey).where(DeviceKey.device_id.in_([alice.device_id, alice2.device_id]))).all()
            == []
        )
        active = db.scalars(select(Device).where(Device.user_id == alice.id, Device.revoked_at.is_(None))).all()
        assert [d.id for d in active] == [new["device"]["id"]]
    # the other user sees the old devices go (devices_changed) and no longer has to encrypt for them
    assert any(e["data"] == {"user_id": alice.id} for e in bob.events("devices_changed"))
    assert bob.send(cid, "to nobody yet").status_code == 201  # alice's new device has no keys: nothing to encrypt for


def test_the_other_users_sessions_are_untouched(admin, alice, bob, client):
    code = issue(admin, alice)["code"]
    assert reset(client, code).status_code == 200
    assert bob.get("/users/me").status_code == 200 and admin.get("/users/me").status_code == 200


def test_reset_is_rate_limited_like_redeem(make_app):
    app, client = make_app(rate_limit_enabled=True)
    codes = [reset(client, "AAAAA-BBBBB-CCCCC-DDDDD").status_code for _ in range(11)]
    assert codes[:10] == [404] * 10 and codes[10] == 429


def test_reset_works_at_the_device_limit(make_app):
    app, client = make_app(max_devices_per_user=1)
    from tests.conftest import Factory

    f = Factory(app, client)
    boss = f.user("boss", admin=True)
    kid = f.user("kid")
    code = issue(boss, kid)["code"]
    assert reset(client, code).status_code == 200  # the old device is revoked first, so the limit is not hit


def test_audit_rows_exist_and_never_hold_the_code(admin, alice, client, app):
    out = issue(admin, alice)
    assert reset(client, out["code"]).status_code == 200
    rows = admin.get("/admin/audit?limit=200").json()
    issued = [r for r in rows if r["action"] == "password_reset_issued"]
    used = [r for r in rows if r["action"] == "password_reset_used"]
    assert len(issued) == 1 and len(used) == 1
    assert issued[0]["actor_user_id"] == admin.id and issued[0]["target_user_id"] == alice.id
    assert used[0]["actor_user_id"] == alice.id and used[0]["details"]["revoked_devices"] == 1
    assert issued[0]["target_id"] == used[0]["target_id"]
    plain = out["code"]
    squashed = plain.replace("-", "")
    with app.state.session_factory() as db:
        for ev in db.scalars(select(AuditEvent)):
            blob = f"{ev.action} {ev.target_id} {ev.ip} {ev.details}"
            assert plain not in blob and squashed not in blob and NEW_PASSWORD not in blob
    assert plain not in str(rows) and NEW_PASSWORD not in str(rows)

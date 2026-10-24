import time

import jwt

from app import clock
from app.models import Device, Invitation
from app.models import Session as AuthSession
from tests.conftest import PASSWORD, SECRET

REDEEM = "/api/v1/auth/invite/redeem"


def redeem_body(code, username="newbie", **kw):
    body = {"code": code, "username": username, "password": PASSWORD, "display_name": "New", "device_name": "Phone"}
    body.update(kw)
    return body


def test_no_signup_without_invite(client):
    r = client.post(REDEEM, json=redeem_body("AAAAA-BBBBB-CCCCC-DDDDD"))
    assert r.status_code == 404
    assert r.json()["code"] == "invalid_invite"
    assert client.post("/api/v1/auth/register", json={}).status_code in (404, 405)


def test_redeem_creates_user_and_device_and_normalises(factory, client):
    code = factory.invite()
    r = client.post(REDEEM, json=redeem_body(code.lower().replace("-", " "), username="  NewBie "))
    assert r.status_code == 201, r.text
    d = r.json()
    assert d["user"]["username"] == "newbie"
    assert d["user"]["is_admin"] is False
    assert d["device"]["number"] == 1 and d["device"]["identity_key"] is None
    assert d["access_expires_at"] > clock.now_ms()
    assert d["refresh_expires_at"] > d["access_expires_at"]


def test_invite_is_single_use(factory, client):
    code = factory.invite()
    assert client.post(REDEEM, json=redeem_body(code, "first")).status_code == 201
    r = client.post(REDEEM, json=redeem_body(code, "second"))
    assert (r.status_code, r.json()["code"]) == (404, "invalid_invite")


def test_expired_invite(factory, client, app):
    code = factory.invite()
    with app.state.session_factory() as db:
        for inv in db.query(Invitation):
            inv.expires_at = clock.now_ms() - 1
        db.commit()
    assert client.post(REDEEM, json=redeem_body(code)).json()["code"] == "invalid_invite"


def test_revoked_invite(factory, client, admin):
    code = factory.invite()
    listing = admin.get("/admin/invitations").json()
    inv_id = next(i["id"] for i in listing if i["revoked_at"] is None and i["used_at"] is None)
    assert admin.delete(f"/admin/invitations/{inv_id}").status_code == 204
    assert client.post(REDEEM, json=redeem_body(code)).json()["code"] == "invalid_invite"
    assert admin.delete("/admin/invitations/nope").status_code == 404


def test_username_taken_keeps_invite_usable(factory, client, alice):
    code = factory.invite()
    r = client.post(REDEEM, json=redeem_body(code, "alice"))
    assert (r.status_code, r.json()["code"]) == (409, "username_taken")
    assert client.post(REDEEM, json=redeem_body(code, "alice2")).status_code == 201


def test_redeem_validation(factory, client):
    code = factory.invite()
    r = client.post(REDEEM, json=redeem_body(code, "x"))
    assert r.status_code == 422 and r.json()["code"] == "invalid_request"
    assert "password" not in r.text.lower() or PASSWORD not in r.text
    r = client.post(REDEEM, json=redeem_body(code, "okname", password="short"))
    assert r.status_code == 422


def test_admin_only_invitation_creation(factory, alice, admin, client):
    assert alice.post("/admin/invitations", {}).status_code == 403
    assert alice.get("/admin/invitations").status_code == 403
    assert alice.delete(f"/admin/users/{admin.id}").status_code == 403
    assert client.post("/api/v1/admin/invitations", json={}).status_code == 401
    r = admin.post("/admin/invitations", {"note": "for grandma", "expires_in_hours": 24, "is_admin": True})
    assert r.status_code == 201
    body = r.json()
    assert body["code"] and body["is_admin"] is True
    # the code is shown only once
    listing = admin.get("/admin/invitations").json()
    assert all("code" not in i for i in listing)
    redeemed = client.post(REDEEM, json=redeem_body(body["code"], "grandma")).json()
    assert redeemed["user"]["is_admin"] is True
    assert admin.post("/admin/invitations", {"expires_in_hours": 0}).status_code == 422
    assert admin.post("/admin/invitations", {"expires_in_hours": 721}).status_code == 422


def test_login_creates_new_device_each_time(factory, alice, client):
    second = factory.login(alice, "Tablet", keys=False)
    third = factory.login(alice, "Laptop", keys=False)
    assert [alice.device["number"], second.device["number"], third.device["number"]] == [1, 2, 3]
    assert len({alice.device_id, second.device_id, third.device_id}) == 3


def test_login_errors_are_uniform(factory, alice, client):
    wrong = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "nope-nope-nope", "device_name": "x"}
    )
    unknown = client.post(
        "/api/v1/auth/login", json={"username": "ghost", "password": "nope-nope-nope", "device_name": "x"}
    )
    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json() == unknown.json()
    assert wrong.json()["code"] == "invalid_credentials"


def test_refresh_rotation_and_reuse_detection(factory, alice, client):
    r1 = client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh})
    assert r1.status_code == 200
    new = r1.json()
    assert new["refresh_token"] != alice.refresh
    assert new["device"]["id"] == alice.device_id
    # new token works
    ok = client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {new['access_token']}"})
    assert ok.status_code == 200
    # replaying the old (rotated) token revokes the whole session and device
    reuse = client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh})
    assert reuse.status_code == 401 and reuse.json()["code"] == "token_reused"
    again = client.post("/api/v1/auth/refresh", json={"refresh_token": new["refresh_token"]})
    assert again.status_code == 401
    dead = client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {new['access_token']}"})
    assert dead.status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": "garbage"}).json()["code"] == "invalid_token"


def test_refresh_token_stored_hashed_and_expiry(factory, alice, client, app):
    with app.state.session_factory() as db:
        s = db.query(AuthSession).one()
        assert s.refresh_token_hash != alice.refresh and len(s.refresh_token_hash) == 64
        s.expires_at = clock.now_ms() - 1
        db.commit()
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh})
    assert r.status_code == 401


def test_logout_revokes_session_device_and_keys(factory, alice, bob, client, app):
    conv = factory.direct(alice, bob)
    assert alice.post("/auth/logout").status_code == 204
    assert alice.get("/users/me").status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh}).status_code == 401
    # keys are gone and bob learns about it
    assert bob.get(f"/keys/{alice.id}").json()["devices"] == []
    assert any(e["type"] == "devices_changed" for e in bob.events())
    with app.state.session_factory() as db:
        assert db.get(Device, alice.device_id).revoked_at is not None
    assert conv


def test_expired_access_token(alice, client):
    expired = jwt.encode(
        {"sub": alice.id, "sid": "x", "did": alice.device_id, "exp": int(time.time()) - 5}, SECRET, algorithm="HS256"
    )
    r = client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {expired}"})
    assert r.status_code == 401 and r.json()["code"] == "token_expired"


def test_bad_tokens(alice, client):
    for h in ({}, {"Authorization": "Bearer nonsense"}, {"Authorization": "Basic abc"}):
        r = client.get("/api/v1/users/me", headers=h)
        assert r.status_code == 401 and r.json()["code"] == "unauthorized"
    forged = jwt.encode(
        {"sub": alice.id, "sid": "x", "did": "y", "exp": int(time.time()) + 99}, "other-secret" * 4, algorithm="HS256"
    )
    assert client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {forged}"}).status_code == 401


def test_rate_limit_login(make_app):
    app, client = make_app(rate_limit_enabled=True)
    body = {"username": "ghost", "password": "wrong-password", "device_name": "x"}
    codes = [client.post("/api/v1/auth/login", json=body).status_code for _ in range(6)]
    assert codes[:5] == [401] * 5
    assert codes[5] == 429
    r = client.post("/api/v1/auth/login", json=body)
    assert r.json()["code"] == "rate_limited" and int(r.headers["Retry-After"]) >= 1


def test_rate_limit_per_ip_and_other_endpoints(make_app):
    app, client = make_app(rate_limit_enabled=True)
    codes = [
        client.post(
            "/api/v1/auth/login", json={"username": f"u{i}", "password": "wrong-password", "device_name": "x"}
        ).status_code
        for i in range(12)
    ]
    assert codes[:10] == [401] * 10 and codes[10:] == [429, 429]
    codes = [client.post(REDEEM, json=redeem_body("AAAAA-AAAAA-AAAAA-AAAAA")).status_code for _ in range(11)]
    assert codes[-1] == 429 and set(codes[:-1]) == {404}
    codes = [client.post("/api/v1/auth/refresh", json={"refresh_token": "x"}).status_code for _ in range(31)]
    assert codes[-1] == 429


def test_rate_limit_key_fetch_per_device(make_app):
    from tests.conftest import Factory

    app, client = make_app(rate_limit_enabled=True)
    f = Factory(app, client)
    # redeem is limited to 10/min per IP, so create users via direct invites
    a = f.user("alice")
    b = f.user("bob")
    codes = [a.get(f"/keys/{b.id}").status_code for _ in range(121)]
    assert codes[119] == 200 and codes[120] == 429


def test_forwarded_for_only_from_trusted_proxy(make_app):
    app, client = make_app(rate_limit_enabled=True, trusted_proxies="10.0.0.0/8")
    body = {"username": "ghost", "password": "wrong-password", "device_name": "x"}
    # the test client's peer is "testclient" (not a trusted IP), so X-Forwarded-For is ignored
    for i in range(10):
        client.post("/api/v1/auth/login", json={**body, "username": f"g{i}"}, headers={"X-Forwarded-For": f"1.2.3.{i}"})
    r = client.post("/api/v1/auth/login", json=body, headers={"X-Forwarded-For": "9.9.9.9"})
    assert r.status_code == 429


def test_client_ip_resolution():
    import ipaddress

    from starlette.requests import Request

    from app.ratelimit import client_ip

    def req(peer, xff):
        scope = {
            "type": "http",
            "client": (peer, 1234),
            "headers": [(b"x-forwarded-for", xff.encode())] if xff else [],
        }
        return Request(scope)

    trusted = [ipaddress.ip_network("172.16.0.0/12"), ipaddress.ip_network("127.0.0.1/32")]
    assert client_ip(req("172.18.0.2", "203.0.113.5"), trusted) == "203.0.113.5"
    assert client_ip(req("172.18.0.2", "6.6.6.6, 203.0.113.5"), trusted) == "203.0.113.5"  # spoofed prefix ignored
    assert client_ip(req("172.18.0.2", "203.0.113.5, 172.18.0.9"), trusted) == "203.0.113.5"
    assert client_ip(req("198.51.100.1", "6.6.6.6"), trusted) == "198.51.100.1"
    assert client_ip(req("172.18.0.2", ""), trusted) == "172.18.0.2"
    assert client_ip(req("172.18.0.2", "1.1.1.1"), []) == "172.18.0.2"


def test_admin_removes_user_sessions_die_immediately(factory, admin, alice, bob, client):
    conv = factory.group(alice, [bob])
    assert bob.get("/users/me").status_code == 200
    assert admin.delete(f"/admin/users/{bob.id}").status_code == 204
    assert bob.get("/users/me").status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": bob.refresh}).status_code == 401
    assert (
        client.post(
            "/api/v1/auth/login", json={"username": "bob", "password": PASSWORD, "device_name": "x"}
        ).status_code
        == 401
    )
    assert bob.id not in [u["id"] for u in admin.get("/users").json()]
    # removed from conversations; remaining member notified
    c = alice.get(f"/conversations/{conv}").json()
    assert [m["user_id"] for m in c["members"]] == [alice.id]
    assert any(e["type"] == "user_removed" and e["data"]["user_id"] == bob.id for e in alice.events())
    assert admin.delete(f"/admin/users/{bob.id}").status_code == 404
    assert admin.delete(f"/admin/users/{admin.id}").status_code == 409


def test_change_password_revokes_other_sessions(factory, alice, client):
    other = factory.login(alice, "Other", keys=False)
    r = alice.put("/users/me/password", {"current_password": "wrong-password!", "new_password": "a brand new password"})
    assert r.status_code == 403
    r = alice.put("/users/me/password", {"current_password": PASSWORD, "new_password": "a brand new password"})
    assert r.status_code == 204
    assert other.get("/users/me").status_code == 401
    assert alice.get("/users/me").status_code == 200
    ok = client.post(
        "/api/v1/auth/login", json={"username": "alice", "password": "a brand new password", "device_name": "x"}
    )
    assert ok.status_code == 200


def test_profile_update_and_users_list(alice, bob):
    r = alice.patch("/users/me", {"display_name": "Alicia"})
    assert r.status_code == 200 and r.json()["display_name"] == "Alicia"
    assert alice.patch("/users/me", {"display_name": ""}).status_code == 422
    assert alice.patch("/users/me", {"avatar_attachment_id": "00000000-0000-4000-8000-000000000000"}).status_code == 422
    assert {u["username"] for u in bob.get("/users").json()} == {"alice", "bob"}
    assert any(e["type"] == "user_updated" and e["data"]["display_name"] == "Alicia" for e in bob.events())


def test_devices_endpoints(factory, alice, bob):
    second = factory.login(alice, "Tablet", keys=False)
    devs = bob.get(f"/users/{alice.id}/devices").json()
    assert [d["number"] for d in devs] == [1, 2]
    assert devs[0]["identity_key"] and devs[1]["identity_key"] is None
    assert alice.get("/devices/me").json()["id"] == alice.device_id
    assert bob.delete(f"/devices/{second.device_id}").status_code == 404
    assert alice.delete(f"/devices/{second.device_id}").status_code == 204
    assert second.get("/users/me").status_code == 401
    assert [d["number"] for d in bob.get(f"/users/{alice.id}/devices").json()] == [1]
    assert bob.get("/users/00000000-0000-4000-8000-000000000000/devices").status_code == 404

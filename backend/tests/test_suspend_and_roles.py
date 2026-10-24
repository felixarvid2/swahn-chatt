"""Admin suspend/unsuspend and promote/demote (with last-admin protection)."""

import threading

import pytest
from starlette.websockets import WebSocketDisconnect

from tests.conftest import PASSWORD

WS = "/api/v1/ws"


def actions(admin, action=None):
    rows = admin.get("/admin/audit?limit=200").json()
    # (the fixture admin's own promotion through the invitation is not what these tests are about)
    return [r for r in rows if (action is None or r["action"] == action) and r["details"].get("via") != "invitation"]


# ---------------------------------------------------------------- suspend


def test_suspended_user_is_refused_everywhere_and_unsuspending_restores(client, admin, alice, bob, factory):
    cid = factory.direct(alice, bob)
    alice.send(cid, "before")
    assert admin.post(f"/admin/users/{bob.id}/suspend").status_code == 204

    r = bob.get("/users/me")
    assert r.status_code == 403 and r.json()["code"] == "account_suspended"
    assert bob.get("/conversations").status_code == 403
    body = {"id": "11111111-1111-4111-8111-111111111111", "conversation_id": cid, "client_ts": 1, "envelopes": []}
    assert bob.post("/messages", body).status_code == 403
    # refreshing does not work either, and does not burn the session
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": bob.refresh})
    assert r.status_code == 403 and r.json()["code"] == "account_suspended"
    # signing in: wrong password is still the plain 401 (no oracle), the right one is told why
    bad = client.post("/api/v1/auth/login", json={"username": "bob", "password": "wrong-password-xyz", "device_name": "x"})
    assert bad.status_code == 401 and bad.json()["code"] == "invalid_credentials"
    good = client.post("/api/v1/auth/login", json={"username": "bob", "password": PASSWORD, "device_name": "x"})
    assert good.status_code == 403 and good.json()["code"] == "account_suspended"

    # everything is still there: membership, devices, queued messages
    assert admin.get("/admin/users").json()[2]["active_devices"] == 1
    assert len(alice.get(f"/conversations/{cid}").json()["members"]) == 2
    assert alice.get(f"/users/{bob.id}/devices").status_code == 200
    assert alice.send(cid, "while suspended").status_code == 201  # senders keep encrypting for bob's device

    assert admin.post(f"/admin/users/{bob.id}/unsuspend").status_code == 204
    assert bob.get("/users/me").status_code == 200  # the same access token works again
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": bob.refresh}).status_code == 200
    texts = [e for e in bob.events("message")]
    assert len(texts) == 2  # both messages were waiting
    assert client.post("/api/v1/auth/login", json={"username": "bob", "password": PASSWORD, "device_name": "y"}).status_code == 200


def test_suspension_closes_open_websockets(client, admin, bob):
    with client.websocket_connect(WS, headers=bob.headers) as ws:
        assert ws.receive_json()["type"] == "hello"
        assert admin.post(f"/admin/users/{bob.id}/suspend").status_code == 204
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4401
    with client.websocket_connect(WS, headers=bob.headers) as ws:  # and no new socket either
        with pytest.raises(WebSocketDisconnect):
            ws.receive_json()


def test_suspend_rules(admin, alice, factory):
    assert admin.post(f"/admin/users/{admin.id}/suspend").status_code == 409
    assert admin.post("/admin/users/00000000-0000-4000-8000-000000000000/suspend").status_code == 404
    assert admin.post("/admin/users/00000000-0000-4000-8000-000000000000/unsuspend").status_code == 404
    assert alice.post(f"/admin/users/{admin.id}/suspend").status_code == 403
    assert alice.post(f"/admin/users/{admin.id}/unsuspend").status_code == 403
    gone = factory.user("gone")
    assert admin.delete(f"/admin/users/{gone.id}").status_code == 204
    assert admin.post(f"/admin/users/{gone.id}/suspend").status_code == 404  # removed accounts cannot be suspended


def test_suspend_is_idempotent_audited_and_listed(admin, alice):
    assert admin.post(f"/admin/users/{alice.id}/suspend").status_code == 204
    assert admin.post(f"/admin/users/{alice.id}/suspend").status_code == 204
    (event,) = actions(admin, "user_suspended")
    assert event["actor_user_id"] == admin.id and event["target_user_id"] == alice.id
    listed = {u["username"]: u for u in admin.get("/admin/users").json()}
    assert listed["alice"]["suspended_at"] is not None and listed["admin"]["suspended_at"] is None
    assert admin.post(f"/admin/users/{alice.id}/unsuspend").status_code == 204
    assert admin.post(f"/admin/users/{alice.id}/unsuspend").status_code == 204
    assert len(actions(admin, "user_unsuspended")) == 1
    assert {u["username"]: u for u in admin.get("/admin/users").json()}["alice"]["suspended_at"] is None


def test_blocked_login_is_audited_and_a_reset_code_does_not_get_a_suspended_user_in(client, admin, alice):
    code = admin.post(f"/admin/users/{alice.id}/password-reset").json()["code"]
    admin.post(f"/admin/users/{alice.id}/suspend")
    client.post("/api/v1/auth/login", json={"username": "alice", "password": PASSWORD, "device_name": "x"})
    blocked = actions(admin, "login_blocked")
    assert len(blocked) == 1 and blocked[0]["details"] == {"reason": "suspended"}
    r = client.post(
        "/api/v1/auth/password/reset", json={"code": code, "new_password": "another long password", "device_name": "x"}
    )
    assert r.status_code == 404 and r.json()["code"] == "invalid_reset_code"
    admin.post(f"/admin/users/{alice.id}/unsuspend")  # the code is still good afterwards
    r = client.post(
        "/api/v1/auth/password/reset", json={"code": code, "new_password": "another long password", "device_name": "x"}
    )
    assert r.status_code == 200


def test_suspended_users_stay_in_the_user_list(alice, bob, admin):
    admin.post(f"/admin/users/{bob.id}/suspend")
    assert bob.id in {u["id"] for u in alice.get("/users").json()}


# ---------------------------------------------------------------- promote / demote


def test_promote_and_demote(admin, alice, factory):
    assert alice.get("/admin/stats").status_code == 403
    r = admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    assert r.status_code == 200 and r.json()["is_admin"] is True and r.json()["username"] == "alice"
    assert alice.get("/admin/stats").status_code == 200  # takes effect on the very next request
    assert alice.get("/users/me").json()["is_admin"] is True
    r = admin.patch(f"/admin/users/{alice.id}", {"is_admin": False})
    assert r.status_code == 200 and r.json()["is_admin"] is False
    assert alice.get("/admin/stats").status_code == 403
    granted, revoked = actions(admin, "admin_granted"), actions(admin, "admin_revoked")
    assert len(granted) == 1 and granted[0]["details"]["via"] == "admin" and granted[0]["target_user_id"] == alice.id
    assert len(revoked) == 1 and revoked[0]["actor_user_id"] == admin.id


def test_role_change_is_announced_to_other_users(admin, alice):
    alice.sync()
    admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    assert any(e["type"] == "user_updated" for e in alice.sync()["events"])


def test_role_change_without_change_is_a_quiet_noop(admin, alice):
    assert admin.patch(f"/admin/users/{alice.id}", {"is_admin": False}).status_code == 200
    assert not actions(admin, "admin_revoked") and not actions(admin, "admin_granted")


def test_patch_user_validation_and_access(admin, alice):
    assert admin.patch(f"/admin/users/{alice.id}", {}).status_code == 422
    assert admin.patch(f"/admin/users/{alice.id}", {"is_admin": "maybe"}).status_code == 422
    assert admin.patch("/admin/users/00000000-0000-4000-8000-000000000000", {"is_admin": True}).status_code == 404
    assert alice.patch(f"/admin/users/{alice.id}", {"is_admin": True}).status_code == 403


def test_the_last_administrator_cannot_be_demoted_not_even_by_themselves(admin, alice):
    r = admin.patch(f"/admin/users/{admin.id}", {"is_admin": False})
    assert r.status_code == 409 and r.json()["code"] == "last_admin"
    assert admin.get("/admin/stats").status_code == 200
    admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    assert admin.patch(f"/admin/users/{admin.id}", {"is_admin": False}).status_code == 200  # alice remains
    r = alice.patch(f"/admin/users/{alice.id}", {"is_admin": False})
    assert r.status_code == 409 and r.json()["code"] == "last_admin"


def test_suspended_and_removed_admins_do_not_count_as_active(admin, alice, factory):
    admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    admin.post(f"/admin/users/{alice.id}/suspend")
    assert admin.patch(f"/admin/users/{admin.id}", {"is_admin": False}).status_code == 409
    admin.post(f"/admin/users/{alice.id}/unsuspend")
    assert admin.patch(f"/admin/users/{admin.id}", {"is_admin": False}).status_code == 200


def test_two_admins_cannot_demote_each_other_at_the_same_time(admin, alice):
    admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    start, results = threading.Barrier(2), []

    def demote(actor, target):
        start.wait()
        results.append(actor.patch(f"/admin/users/{target.id}", {"is_admin": False}).status_code)

    threads = [threading.Thread(target=demote, args=(admin, alice)), threading.Thread(target=demote, args=(alice, admin))]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    assert sorted(results) in ([200, 403], [200, 409])  # never two successes
    users = {u["username"]: u for u in admin_listing(admin, alice)}
    assert sum(u["is_admin"] for u in users.values()) == 1


def admin_listing(*actors):
    for a in actors:
        r = a.get("/admin/users")
        if r.status_code == 200:
            return r.json()
    raise AssertionError("nobody can list users")

"""DELETE /users/me: the user removes their own account after re-entering the password."""

import uuid

from sqlalchemy import select

from app.models import Attachment, User
from app.services.maintenance import prune
from tests.conftest import PASSWORD

PNG = b"\x89PNG\r\n\x1a\n" + b"\0" * 32


def audit_actions(admin, action):
    return [e for e in admin.get("/admin/audit?limit=200").json() if e["action"] == action]


def upload(actor, purpose, data, content_type):
    r = actor.post(f"/attachments?purpose={purpose}", content=data, headers={"Content-Type": content_type})
    assert r.status_code == 201, r.text
    return r.json()["id"]


def test_wrong_or_missing_password_changes_nothing(client, alice, admin):
    r = alice.delete("/users/me", json={"password": "not the password"})
    assert r.status_code == 403 and r.json()["code"] == "invalid_credentials"
    assert alice.delete("/users/me").status_code == 422
    assert alice.get("/users/me").status_code == 200
    assert [e["target_user_id"] for e in audit_actions(admin, "self_delete_failed")] == [alice.id]


def test_deleting_disables_the_account_like_an_admin_removal(client, factory, alice, bob, admin):
    cid = factory.direct(alice, bob)
    sent = alice.send(cid, "my last words").json()["message"]["id"]
    second = factory.login(alice, "tablet")
    bob.sync()
    r = alice.delete("/users/me", json={"password": PASSWORD})
    assert r.status_code == 204
    for device in (alice, second):  # every device and session is revoked
        assert device.get("/users/me").status_code == 401
    login = client.post("/api/v1/auth/login", json={"username": "alice", "password": PASSWORD, "device_name": "x"})
    assert login.status_code == 401
    assert alice.id not in {u["id"] for u in bob.get("/users").json()}
    types = [e["type"] for e in bob.sync()["events"]]
    assert "user_removed" in types
    # the conversation and the messages stay for the others
    history = bob.get(f"/conversations/{cid}/messages").json()
    assert [m["id"] for m in history] == [sent]
    assert [m["user_id"] for m in bob.get(f"/conversations/{cid}").json()["members"]] == [bob.id]
    (event,) = audit_actions(admin, "user_removed")
    assert event["actor_user_id"] == alice.id == event["target_user_id"] and event["details"]["via"] == "self"
    user = next(u for u in admin.get("/admin/users").json() if u["username"] == "alice")
    assert user["disabled_at"] is not None and user["active_devices"] == 0


def test_the_last_administrator_cannot_delete_themselves(admin, alice):
    r = admin.delete("/users/me", json={"password": PASSWORD})
    assert r.status_code == 409 and r.json()["code"] == "last_admin"
    assert admin.get("/admin/stats").status_code == 200
    admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    assert admin.delete("/users/me", json={"password": PASSWORD}).status_code == 204
    r = alice.delete("/users/me", json={"password": PASSWORD})  # now alice is the last one
    assert r.status_code == 409


def test_suspended_admins_do_not_count_as_a_second_admin(admin, alice):
    admin.patch(f"/admin/users/{alice.id}", {"is_admin": True})
    admin.post(f"/admin/users/{alice.id}/suspend")
    assert admin.delete("/users/me", json={"password": PASSWORD}).status_code == 409


def test_avatar_and_unreferenced_files_become_collectable_but_sent_files_stay(app, factory, alice, bob):
    cid = factory.direct(alice, bob)
    avatar = upload(alice, "avatar", PNG, "image/png")
    assert alice.patch("/users/me", {"avatar_attachment_id": avatar}).status_code == 200
    unused = upload(alice, "message", b"never sent", "application/octet-stream")
    sent_file = upload(alice, "message", b"sent in a message", "application/octet-stream")
    assert alice.send(cid, "with file", attachment_ids=[sent_file]).status_code == 201
    assert alice.delete("/users/me", json={"password": PASSWORD}).status_code == 204
    with app.state.session_factory() as db:
        assert db.get(User, alice.id).avatar_attachment_id is None
        result = prune(db, app.state.settings)  # well within the usual 24 hour grace period
        left = set(db.scalars(select(Attachment.id)))
    assert result["attachments"] == 2
    assert left == {sent_file}
    assert bob.get(f"/attachments/{sent_file}").content == b"sent in a message"


def test_deleting_is_rate_limited(make_app, factory):
    app, client = make_app(rate_limit_enabled=True)
    from tests.conftest import Factory

    alice = Factory(app, client).user("alice")
    codes = [alice.delete("/users/me", json={"password": f"wrong-{uuid.uuid4()}"}).status_code for _ in range(7)]
    assert codes[:5] == [403] * 5 and codes[5:] == [429, 429]

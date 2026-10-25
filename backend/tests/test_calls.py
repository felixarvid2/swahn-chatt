"""1:1 calls: signalling messages (kind "call") and TURN REST credentials. The server only relays ciphertext."""

import base64
import hashlib
import hmac
import json
import uuid

import pytest
from sqlalchemy import func, select, update

from app import clock
from app.config import ConfigError, Settings
from app.models import Conversation, Message, MessageEnvelope
from app.services.maintenance import prune
from tests.conftest import SECRET, Factory

ENDPOINT = "https://ntfy.example.org/upABCDEF123?up=1"
TURN_SECRET = "turn-secret-0123456789-abcdefghijklmnopqrstuvwxyz"
TURN = "turn:pi.example.ts.net:3478?transport=udp,turns:pi.example.ts.net:5349?transport=tcp"


def call(actor, cid, **extra):
    return actor.send(cid, "offer", kind="call", **extra)


def count(app, model, *where):
    with app.state.session_factory() as db:
        return db.scalar(select(func.count()).select_from(model).where(*where))


def run_prune(app):
    with app.state.session_factory() as db:
        return prune(db, app.state.settings)


def age(app, message_id, seconds):
    with app.state.session_factory() as db:
        db.execute(update(Message).where(Message.id == message_id).values(server_ts=clock.now_ms() - seconds * 1000))
        db.commit()


# ---- signalling messages


def test_call_reaches_every_device_of_both_users_but_not_the_sender(factory, alice, bob):
    alice2 = factory.login(alice, "tablet")
    bob2 = factory.login(bob, "tablet")
    cid = factory.direct(alice, bob)
    r = call(alice, cid)
    assert r.status_code == 201, r.text
    msg = r.json()["message"]
    assert msg["kind"] == "call" and msg["target_message_id"] is None and msg["expires_at"] is None
    for dev in (alice2, bob, bob2):
        (ev,) = [e for e in dev.events("message") if e["data"]["kind"] == "call"]
        assert ev["data"]["id"] == msg["id"] and ev["conversation_id"] == cid
        assert base64.b64decode(ev["data"]["envelope"]["body"]) == f"ct|offer|{dev.device_id}".encode()
    assert [e for e in alice.events("message") if e["data"]["kind"] == "call"] == []


def test_call_needs_the_full_device_set(factory, alice, bob):
    cid = factory.direct(alice, bob)
    r = call(alice, cid, envelopes=[])
    assert r.status_code == 409 and r.json()["code"] == "mismatched_devices"


def test_call_only_in_direct_conversations(factory, alice, bob, carol):
    gid = factory.group(alice, [bob, carol])
    r = call(alice, gid)
    assert r.status_code == 409 and r.json()["code"] == "calls_direct_only"
    assert count(factory.app, Message, Message.kind == "call") == 0


def test_call_validation(factory, alice, bob, carol):
    cid = factory.direct(alice, bob)
    target = bob.send(cid, "x").json()["message"]
    assert call(alice, cid, target_message_id=target["id"]).status_code == 422
    att = alice.post(
        "/attachments?purpose=message", content=b"abc", headers={"Content-Type": "application/octet-stream"}
    ).json()
    assert call(alice, cid, attachment_ids=[att["id"]]).status_code == 422
    body = {"id": str(uuid.uuid4()), "conversation_id": cid, "kind": "call", "client_ts": 1, "envelopes": []}
    assert carol.post("/messages", body).status_code == 404


def test_call_is_idempotent_by_id(factory, alice, bob):
    cid = factory.direct(alice, bob)
    mid = str(uuid.uuid4())
    assert call(alice, cid, message_id=mid).status_code == 201
    again = call(alice, cid, message_id=mid)
    assert again.status_code == 200 and again.json()["duplicate"] is True


def test_call_does_not_touch_conversation_unread_or_history(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    normal = alice.send(cid, "hello").json()["message"]
    before = bob.get(f"/conversations/{cid}").json()
    with app.state.session_factory() as db:
        updated_before = db.get(Conversation, cid).updated_at
    assert call(alice, cid).status_code == 201
    assert call(bob, cid).status_code == 201
    after = bob.get(f"/conversations/{cid}").json()
    assert after["last_message_seq"] == before["last_message_seq"] == normal["seq"]
    assert after["last_message_at"] == before["last_message_at"]
    assert after["updated_at"] == before["updated_at"] == updated_before
    assert after["unread_count"] == before["unread_count"] == 1
    for who in (alice, bob):
        history = who.get(f"/conversations/{cid}/messages").json()
        assert [m["id"] for m in history] == [normal["id"]]


def test_call_is_not_acknowledged_with_delivery_receipts_and_ignores_the_disappearing_timer(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    assert alice.patch(f"/conversations/{cid}", {"disappearing_seconds": 300}).status_code == 200
    sent = call(alice, cid).json()["message"]
    assert sent["expires_at"] is None
    alice.ack(alice.sync()["next_cursor"])
    bob.ack(bob.sync()["next_cursor"])
    assert [e for e in alice.events("receipt")] == []


def test_call_cannot_be_target_of_edit_delete_or_reaction(factory, alice, bob):
    cid = factory.direct(alice, bob)
    sent = call(alice, cid).json()["message"]
    for kind in ("edit", "delete", "reaction"):
        r = alice.send(cid, "x", kind=kind, target_message_id=sent["id"])
        assert r.status_code == 422, (kind, r.text)


def test_prune_removes_old_calls_and_their_envelopes_even_when_undelivered(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    old = call(alice, cid).json()["message"]
    fresh = call(alice, cid).json()["message"]
    normal = alice.send(cid, "keep me").json()["message"]
    assert app.state.settings.call_retention_seconds == 600
    age(app, old["id"], 601)
    age(app, fresh["id"], 599)
    age(app, normal["id"], 100000)  # regular messages are not subject to the call retention
    assert run_prune(app)["messages"] == 1
    assert count(app, Message, Message.id == old["id"]) == 0
    assert count(app, MessageEnvelope, MessageEnvelope.message_id == old["id"]) == 0
    assert count(app, Message, Message.id == fresh["id"]) == 1
    assert count(app, Message, Message.id == normal["id"]) == 1
    # the event of the deleted call is stale: the device skips it but its cursor still advances
    kinds = [e["data"]["kind"] for e in bob.events("message")]
    assert sorted(kinds) == ["call", "message"]


def test_call_retention_is_configurable(make_app):
    app, client = make_app(call_retention_seconds=30)
    f = Factory(app, client)
    a, b = f.user("alice"), f.user("bob")
    cid = f.direct(a, b)
    mid = call(a, cid).json()["message"]["id"]
    age(app, mid, 31)
    assert run_prune(app)["messages"] == 1


def test_call_messages_are_rate_limited_per_sender(make_app):
    app, client = make_app(rate_limit_enabled=True)
    f = Factory(app, client)
    a, b = f.user("alice"), f.user("bob")
    cid = f.direct(a, b)
    for _ in range(60):
        assert call(a, cid).status_code == 201
    r = call(a, cid)
    assert r.status_code == 429 and r.json()["code"] == "rate_limited" and "retry-after" in r.headers
    assert a.send(cid, "ordinary messages are not affected").status_code == 201
    assert call(b, cid).status_code == 201  # per sender


# ---- push


def test_call_push_is_high_priority_and_content_free_ordinary_push_is_not(factory, app, alice, bob):
    cid = factory.direct(alice, bob)
    app.state.push.flush()
    assert bob.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT}).status_code == 204
    alice.send(cid, "ordinary")
    app.state.push.flush()
    (normal,) = app.state.pushes
    assert "priority" not in normal.headers and json.loads(normal.content) == {"t": "sync"}
    call(alice, cid)
    app.state.push.flush()
    assert len(app.state.pushes) == 2
    urgent = app.state.pushes[1]
    assert json.loads(urgent.content) == {"t": "sync"}
    assert urgent.headers["priority"] == "5" and urgent.headers["urgency"] == "high"
    blob = (str(urgent.url) + urgent.content.decode() + str(dict(urgent.headers))).lower()
    for forbidden in (alice.username, alice.id, cid, "ct|", "offer", "call"):
        assert forbidden not in blob.replace("urgency", "")


def test_notify_urgent_sets_priority_only_for_that_push(tmp_path, new_settings):
    import httpx

    from app.main import create_app

    sent: list[httpx.Request] = []
    app = create_app(
        new_settings(tmp_path / "d"),
        push_transport=httpx.MockTransport(lambda r: sent.append(r) or httpx.Response(201)),
    )
    from fastapi.testclient import TestClient

    with TestClient(app) as client:
        a = Factory(app, client).user("alice")
        app.state.push.flush()
        assert a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT}).status_code == 204
        push = app.state.push
        push.notify([a.device_id], urgent=[a.device_id])
        push.flush()
        assert sent and sent[-1].headers["priority"] == "5"
        push.notify([a.device_id])
        push.flush()
        assert "priority" not in sent[-1].headers


# ---- TURN credentials


def turn_app(make_app, **kw):
    kw.setdefault("turn_urls", TURN)
    kw.setdefault("turn_secret", TURN_SECRET)
    app, client = make_app(**kw)
    return Factory(app, client).user("alice")


def test_turn_requires_authentication(make_app):
    app, client = make_app(turn_urls=TURN, turn_secret=TURN_SECRET)
    assert client.get("/api/v1/calls/turn").status_code == 401
    assert client.get("/api/v1/calls/turn", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_turn_credentials_follow_the_turn_rest_api(make_app, monkeypatch):
    a = turn_app(make_app, stun_urls="stun:pi.example.ts.net:3478")
    monkeypatch.setattr(clock, "now_ms", lambda: 1_000_000_000_000)
    r = a.get("/calls/turn")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ttl"] == 3600
    assert body["urls"] == ["stun:pi.example.ts.net:3478", *TURN.split(",")]
    assert body["username"] == f"{1_000_000_000 + 3600}:{a.id}"
    expected = base64.b64encode(hmac.new(TURN_SECRET.encode(), body["username"].encode(), hashlib.sha1).digest())
    assert body["credential"] == expected.decode()
    assert set(body) == {"urls", "username", "credential", "ttl"}


def test_turn_credentials_are_per_user_and_expire(make_app, monkeypatch):
    app, client = make_app(turn_urls=TURN, turn_secret=TURN_SECRET, turn_ttl_seconds=120)
    f = Factory(app, client)
    a, b = f.user("alice"), f.user("bob")
    monkeypatch.setattr(clock, "now_ms", lambda: 1_000_000_000_000)
    ca, cb = a.get("/calls/turn").json(), b.get("/calls/turn").json()
    assert ca["ttl"] == 120 and ca["username"].startswith("1000000120:")
    assert ca["username"].endswith(a.id) and cb["username"].endswith(b.id)
    assert ca["credential"] != cb["credential"]
    monkeypatch.setattr(clock, "now_ms", lambda: 1_000_000_060_000)
    later = a.get("/calls/turn").json()
    assert later["username"].startswith("1000000180:") and later["credential"] != ca["credential"]


def test_turn_not_configured_returns_no_relay(make_app):
    a = turn_app(make_app, turn_urls="", turn_secret="")
    assert a.get("/calls/turn").json() == {"urls": [], "username": None, "credential": None, "ttl": 3600}
    b = turn_app(make_app, turn_urls="", turn_secret="", stun_urls="stun:stun.example.org:3478")
    assert b.get("/calls/turn").json()["urls"] == ["stun:stun.example.org:3478"]
    assert b.get("/calls/turn").json()["credential"] is None


def test_turn_is_rate_limited(make_app):
    a = turn_app(make_app, rate_limit_enabled=True)
    for _ in range(30):
        assert a.get("/calls/turn").status_code == 200
    r = a.get("/calls/turn")
    assert r.status_code == 429 and "retry-after" in r.headers


def test_turn_secret_is_validated_and_never_printed(tmp_path, caplog):
    base = dict(env="development", secret_key=SECRET, data_dir=tmp_path)
    with pytest.raises(ConfigError, match="TURN_SECRET"):
        Settings(**base, turn_urls=TURN)
    with pytest.raises(ConfigError, match="TURN_SECRET"):
        Settings(**base, turn_urls=TURN, turn_secret="short")
    with pytest.raises(ConfigError, match="TURN_URLS"):
        Settings(**base, turn_urls="http://evil.example/x", turn_secret=TURN_SECRET)
    with pytest.raises(ConfigError, match="STUN_URLS"):
        Settings(**base, stun_urls="turn:host:3478")
    assert Settings(**base).turn_url_list == []  # unset is fine, no secret required
    from app.services.configcheck import check

    s = Settings(**base, turn_urls=TURN, turn_secret=TURN_SECRET)
    report = check(s, len(SECRET))
    assert TURN_SECRET not in repr(report.settings) and ("turn", "2 url(s)") in report.settings

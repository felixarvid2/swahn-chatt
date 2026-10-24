"""WebSocket: bounded outgoing queue, inbound frame limits, cached membership, and session re-validation."""

import asyncio
import time

import pytest
from sqlalchemy import text
from starlette.websockets import WebSocketDisconnect

import app.hub as hub_module
import app.routers.ws as ws_module
from app.hub import Connection
from app.services import accounts
from tests.conftest import Factory

WS = "/api/v1/ws"


def connect(client, actor):
    return client.websocket_connect(WS, headers=actor.headers)


def expect_close(ws, timeout_msgs=50):
    """Read until the server closes the socket; return (close code, messages received before)."""
    got = []
    for _ in range(timeout_msgs):
        try:
            got.append(ws.receive_json())
        except WebSocketDisconnect as exc:
            return exc.code, got
    raise AssertionError("socket was not closed")


# ---------------------------------------------------------------- bounded queue


def test_connection_queue_is_bounded_and_overflow_closes_with_1008():
    async def scenario():
        conn = Connection("d", "u")
        assert conn.queue.maxsize == hub_module.QUEUE_LIMIT == 256
        for i in range(hub_module.QUEUE_LIMIT):
            conn.offer({"n": i})
        assert not conn.closed.is_set()
        conn.offer({"n": "one too many"})
        assert conn.closed.is_set() and conn.close_code == 1008
        assert conn.queue.qsize() == hub_module.QUEUE_LIMIT

    asyncio.run(scenario())


def test_slow_reader_is_disconnected_instead_of_buffered(monkeypatch, factory, client, alice, bob):
    monkeypatch.setattr(hub_module, "QUEUE_LIMIT", 3)

    async def never_writes(ws, conn):
        await asyncio.Event().wait()

    monkeypatch.setattr(ws_module, "_writer", never_writes)
    cid = factory.direct(alice, bob)
    with connect(client, bob) as ws:
        assert ws.receive_json()["type"] == "hello"
        for _ in range(6):
            assert alice.send(cid, "x").status_code == 201
        code, _ = expect_close(ws)
        assert code == 1008
    # nothing is lost: the messages are still in bob's queue in the database
    assert len([e for e in bob.sync()["events"] if e["type"] == "message"]) == 6


def test_hub_send_from_threads_uses_the_bounded_offer(monkeypatch, factory, client, alice, bob):
    """Overflow raised inside the loop callback used to be swallowed by the loop, leaving the socket open."""
    monkeypatch.setattr(hub_module, "QUEUE_LIMIT", 2)
    cid = factory.direct(alice, bob)
    gate = {}

    async def blocked_writer(ws, conn):
        gate["conn"] = conn
        await asyncio.Event().wait()

    monkeypatch.setattr(ws_module, "_writer", blocked_writer)
    with connect(client, bob) as ws:
        ws.receive_json()
        for _ in range(4):
            alice.send(cid, "y")
        assert expect_close(ws)[0] == 1008
    assert gate["conn"].queue.qsize() <= 2


# ---------------------------------------------------------------- inbound limits


def test_frame_flood_closes_the_socket(monkeypatch, client, alice):
    monkeypatch.setattr(ws_module, "FRAMES_HARD_LIMIT", 20)
    with connect(client, alice) as ws:
        ws.receive_json()
        for _ in range(25):
            ws.send_json({"type": "ping"})
        code, got = expect_close(ws)
        assert code == 1008
        assert len(got) == ws_module.PING_LIMIT == 5  # pings beyond the per-window allowance get no pong


def test_invalid_frames_count_towards_the_flood_limit(monkeypatch, client, alice):
    monkeypatch.setattr(ws_module, "FRAMES_HARD_LIMIT", 10)
    with connect(client, alice) as ws:
        ws.receive_json()
        for _ in range(15):
            ws.send_text("this is not json")
        assert expect_close(ws)[0] == 1008


def test_normal_use_is_not_limited(client, alice):
    with connect(client, alice) as ws:
        ws.receive_json()
        for _ in range(4):
            ws.send_json({"type": "ping"})
            assert ws.receive_json() == {"type": "pong"}
        ws.send_text("garbage")  # a single bad frame is ignored
        ws.send_json({"type": "ping"})
        assert ws.receive_json() == {"type": "pong"}


def test_typing_frames_are_rate_limited_per_connection(monkeypatch, factory, client, alice, bob):
    monkeypatch.setattr(ws_module, "TYPING_LIMIT", 3)
    cid = factory.direct(alice, bob)
    with connect(client, alice) as wa, connect(client, bob) as wb:
        wa.receive_json()
        wb.receive_json()
        for _ in range(8):
            wa.send_json({"type": "typing", "conversation_id": cid, "state": "start"})
        wa.send_json({"type": "ping"})
        assert wa.receive_json() == {"type": "pong"}  # everything before the ping has been processed
        got = []
        wb.send_json({"type": "ping"})
        while (m := wb.receive_json())["type"] != "pong":
            got.append(m)
        assert len(got) == 3 and all(m["type"] == "typing" for m in got)


def test_typing_membership_is_cached_briefly_including_misses(monkeypatch, factory, client, alice, bob, carol):
    calls = []
    real = ws_module._typing_targets

    def counting(app, p, conversation_id):
        calls.append((p.user_id, conversation_id))
        return real(app, p, conversation_id)

    monkeypatch.setattr(ws_module, "_typing_targets", counting)
    cid = factory.direct(alice, bob)
    with connect(client, alice) as wa, connect(client, carol) as wc:
        wa.receive_json()
        wc.receive_json()
        for w in (wa, wc):
            for _ in range(10):
                w.send_json({"type": "typing", "conversation_id": cid, "state": "start"})
            w.send_json({"type": "ping"})
            assert w.receive_json() == {"type": "pong"}
    assert sorted(calls) == sorted([(alice.id, cid), (carol.id, cid)])  # one query each, the non-member's too


def test_membership_cache_expires(monkeypatch, factory, client, alice, bob):
    monkeypatch.setattr(ws_module, "MEMBERSHIP_TTL_S", 0.0)
    calls = []
    real = ws_module._typing_targets
    monkeypatch.setattr(ws_module, "_typing_targets", lambda a, p, c: calls.append(c) or real(a, p, c))
    cid = factory.direct(alice, bob)
    with connect(client, alice) as wa:
        wa.receive_json()
        for _ in range(3):
            wa.send_json({"type": "typing", "conversation_id": cid, "state": "stop"})
        wa.send_json({"type": "ping"})
        assert wa.receive_json() == {"type": "pong"}
    assert len(calls) == 3


# ---------------------------------------------------------------- revocation


def revoke_like_another_process(app, device_id):
    """What `cli` does: a plain database write; this process's hub is not told."""
    with app.state.engine.begin() as conn:
        conn.execute(text("UPDATE devices SET revoked_at = 1 WHERE id = :d"), {"d": device_id})


def test_revocation_between_authentication_and_registration_closes_the_socket(monkeypatch, app, client, alice):
    """The hub kick of a concurrent revocation finds no socket yet; the post-register re-check catches it."""
    real = ws_module.check_session
    calls = []

    def racing(app_, claims):
        calls.append(1)
        result = real(app_, claims)
        if len(calls) == 1:
            with app_.state.session_factory() as db:
                accounts.revoke_device(db, alice.device_id)
                db.commit()
        return result

    monkeypatch.setattr(ws_module, "check_session", racing)
    with connect(client, alice) as ws:
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()  # no hello: straight to the close
        assert exc.value.code == 4401
    assert len(calls) == 2
    assert not app.state.hub.is_connected(alice.device_id)


def test_periodic_revalidation_catches_revocation_from_another_process(make_app):
    app, client = make_app(ws_revalidate_seconds=0.1)
    alice = Factory(app, client).user("alice")
    with connect(client, alice) as ws:
        assert ws.receive_json()["type"] == "hello"
        revoke_like_another_process(app, alice.device_id)
        code, _ = expect_close(ws)
        assert code == 4401


def test_periodic_revalidation_catches_a_disabled_user(make_app):
    app, client = make_app(ws_revalidate_seconds=0.1)
    f = Factory(app, client)
    f.user("admin", admin=True)
    bob = f.user("bob")
    with connect(client, bob) as ws:
        ws.receive_json()
        with app.state.engine.begin() as conn:
            conn.execute(text("UPDATE users SET disabled_at = 1 WHERE id = :u"), {"u": bob.id})
        assert expect_close(ws)[0] == 4401


def test_healthy_socket_survives_revalidation_and_token_expiry_is_not_rechecked(make_app):
    app, client = make_app(ws_revalidate_seconds=0.05)
    alice = Factory(app, client).user("alice")
    with connect(client, alice) as ws:
        ws.receive_json()
        time.sleep(0.4)  # several re-validations
        ws.send_json({"type": "ping"})
        assert ws.receive_json() == {"type": "pong"}


def test_revalidation_ignores_database_trouble(monkeypatch, make_app):
    app, client = make_app(ws_revalidate_seconds=0.05)
    alice = Factory(app, client).user("alice")
    real = ws_module.check_session
    state = {"fail": False}

    def flaky(a, c):
        if state["fail"]:
            raise RuntimeError("database is down")
        return real(a, c)

    monkeypatch.setattr(ws_module, "check_session", flaky)
    with connect(client, alice) as ws:
        ws.receive_json()
        state["fail"] = True
        time.sleep(0.3)
        state["fail"] = False
        ws.send_json({"type": "ping"})
        assert ws.receive_json() == {"type": "pong"}


def test_connecting_refreshes_last_seen(app, client, alice):
    from app import clock
    from app.models import Device

    with app.state.session_factory() as db:
        db.get(Device, alice.device_id).last_seen_at = 1
        db.commit()
    with connect(client, alice) as ws:
        ws.receive_json()
    with app.state.session_factory() as db:
        assert db.get(Device, alice.device_id).last_seen_at > clock.now_ms() - 60_000

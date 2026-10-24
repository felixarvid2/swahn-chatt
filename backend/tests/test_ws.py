import pytest
from starlette.websockets import WebSocketDisconnect

WS = "/api/v1/ws"


def connect(client, actor, **kw):
    return client.websocket_connect(WS, headers=actor.headers, **kw)


def test_hello_reports_latest_cursor(factory, client, alice, bob):
    cid = factory.direct(alice, bob)
    alice.send(cid, "queued while offline")
    expected = bob.sync()["next_cursor"]
    with connect(client, bob) as ws:
        assert ws.receive_json() == {"type": "hello", "cursor": expected}


def test_real_time_sync_hint_and_delivery(factory, client, alice, bob):
    cid = factory.direct(alice, bob)
    with connect(client, bob) as ws:
        assert ws.receive_json()["type"] == "hello"
        r = alice.send(cid, "live")
        assert r.status_code == 201
        hint = ws.receive_json()
        assert hint["type"] == "sync"
        events = bob.sync()["events"]
        assert hint["cursor"] == max(e["seq"] for e in events)
        (msg,) = [e for e in events if e["type"] == "message"]
        assert msg["data"]["id"] == r.json()["message"]["id"]
        # acking produces a delivered receipt; alice (not connected) is not hinted, bob gets nothing more
        bob.ack(hint["cursor"])
        ws.send_json({"type": "ping"})
        assert ws.receive_json() == {"type": "pong"}


def test_hint_is_sent_to_every_connected_device_of_the_recipient(factory, client, alice, bob):
    bob2 = factory.login(bob, "tablet")
    cid = factory.direct(alice, bob)
    with connect(client, bob) as ws1, connect(client, bob2) as ws2:
        ws1.receive_json()
        ws2.receive_json()
        alice.send(cid, "to both")
        assert ws1.receive_json()["type"] == "sync"
        assert ws2.receive_json()["type"] == "sync"


def test_token_via_query_parameter(client, alice):
    with client.websocket_connect(f"{WS}?access_token={alice.access}") as ws:
        assert ws.receive_json()["type"] == "hello"


@pytest.mark.parametrize("token", ["garbage", ""])
def test_invalid_token_closes_with_4401(client, token):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with client.websocket_connect(WS, headers=headers) as ws:
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4401


def test_revoked_session_cannot_connect(client, alice):
    alice.post("/auth/logout")
    with client.websocket_connect(WS, headers=alice.headers) as ws:
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4401


def test_removed_user_is_disconnected(factory, client, admin, bob):
    with connect(client, bob) as ws:
        ws.receive_json()
        assert admin.delete(f"/admin/users/{bob.id}").status_code == 204
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 4401


def test_typing_is_relayed_to_members_only(factory, client, alice, bob, carol):
    cid = factory.direct(alice, bob)
    with connect(client, alice) as wa, connect(client, bob) as wb, connect(client, carol) as wc:
        for w in (wa, wb, wc):
            w.receive_json()
        wa.send_json({"type": "typing", "conversation_id": cid, "state": "start"})
        assert wb.receive_json() == {"type": "typing", "conversation_id": cid, "user_id": alice.id, "state": "start"}
        # carol is not a member: her typing is dropped, and she receives nothing
        wc.send_json({"type": "typing", "conversation_id": cid, "state": "start"})
        wa.send_json({"type": "typing", "conversation_id": cid, "state": "bogus"})  # ignored
        wa.send_json({"type": "typing", "conversation_id": cid, "state": "stop"})
        assert wb.receive_json()["state"] == "stop"
        wc.send_json({"type": "ping"})
        assert wc.receive_json() == {"type": "pong"}
        wa.send_json({"type": "ping"})
        assert wa.receive_json() == {"type": "pong"}  # alice got no echo of her own typing

import base64
import threading
import uuid

from app.models import Event, Message, MessageEnvelope
from tests.conftest import b64


def test_message_delivery_over_sync_and_ack(factory, alice, bob):
    cid = factory.direct(alice, bob)
    r = alice.send(cid, "hello bob")
    assert r.status_code == 201
    j = r.json()
    assert j["duplicate"] is False
    msg = j["message"]
    assert msg["envelope"] is None and msg["seq"] >= 1 and msg["kind"] == "message" and msg["deleted"] is False
    assert msg["sender_user_id"] == alice.id and msg["sender_device_id"] == alice.device_id

    page = bob.sync()
    (ev,) = [e for e in page["events"] if e["type"] == "message"]
    assert ev["conversation_id"] == cid and ev["data"]["id"] == msg["id"]
    expected = f"ct|hello bob|{bob.device_id}".encode()
    assert base64.b64decode(ev["data"]["envelope"]["body"]) == expected
    assert ev["data"]["envelope"]["type"] == "whisper"
    assert page["has_more"] is False and page["next_cursor"] == page["events"][-1]["seq"]
    # the sending device gets no copy of its own message
    assert alice.events("message") == []
    # events are re-delivered until acknowledged
    assert bob.sync()["events"] == page["events"]

    bob.ack(page["next_cursor"])
    assert bob.sync()["events"] == []
    receipts = alice.events("receipt")
    assert [r["data"] for r in receipts] == [
        {"conversation_id": cid, "user_id": bob.id, "type": "delivered", "message_ids": [msg["id"]]}
    ]
    # acking again (or beyond) is harmless and sends no second receipt
    bob.ack(page["next_cursor"])
    bob.ack(page["next_cursor"] + 1000)
    assert len(alice.events("receipt")) == 1


def test_sync_cursor_semantics_for_new_device(factory, alice, bob):
    cid = factory.direct(alice, bob)
    alice.send(cid, "before bob's second phone")
    bob2 = factory.login(bob, "second", keys=False)
    assert bob2.sync()["events"] == []  # a new device only sees events created after it registered
    bob2.upload_keys()
    alice.send(cid, "after")
    assert [e["type"] for e in bob2.events() if e["type"] == "message"] == ["message"]
    assert bob2.sync(after=10**9) == {"events": [], "next_cursor": 10**9, "has_more": False}


def test_message_for_own_other_devices(factory, alice, bob):
    alice2 = factory.login(alice, "tablet")
    cid = factory.direct(alice, bob)
    r = alice.send(cid, "to all")
    assert r.status_code == 201
    (ev,) = alice2.events("message")
    assert base64.b64decode(ev["data"]["envelope"]["body"]).endswith(alice2.device_id.encode())
    # acking an own-user copy yields no delivered receipt to oneself
    alice2.ack(alice2.sync()["next_cursor"])
    assert alice.events("receipt") == []


def test_envelope_set_mismatch_returns_409(factory, alice, bob):
    alice2 = factory.login(alice, "tablet")
    cid = factory.direct(alice, bob)
    good = alice.envelopes(cid, "x")
    assert {e["device_id"] for e in good} == {bob.device_id, alice2.device_id}

    def attempt(envs):
        return alice.post(
            "/messages",
            {"id": str(uuid.uuid4()), "conversation_id": cid, "kind": "message", "client_ts": 1, "envelopes": envs},
        )

    r = attempt([good[0]])
    assert r.status_code == 409
    j = r.json()
    assert j["code"] == "mismatched_devices" and j["message"]
    assert len(j["missing_devices"]) == 1 and j["extra_devices"] == []
    assert {"user_id", "device_id"} == set(j["missing_devices"][0])

    own = {"device_id": alice.device_id, "type": "whisper", "body": b64(b"self")}
    ghost = {"device_id": str(uuid.uuid4()), "type": "whisper", "body": b64(b"ghost")}
    r = attempt(good + [own, ghost])
    j = r.json()
    assert r.status_code == 409 and j["missing_devices"] == []
    assert {d["device_id"] for d in j["extra_devices"]} == {alice.device_id, ghost["device_id"]}
    assert {"user_id": alice.id, "device_id": alice.device_id} in j["extra_devices"]

    assert attempt([]).status_code == 409
    assert attempt(good + [good[0]]).status_code == 422  # duplicate device
    # nothing was stored by the failed attempts
    assert alice.get(f"/conversations/{cid}/messages").json() == []
    assert attempt(good).status_code == 201


def test_unkeyed_devices_are_not_required(factory, alice, bob):
    factory.login(bob, "no keys yet", keys=False)
    cid = factory.direct(alice, bob)
    assert alice.send(cid, "ok").status_code == 201


def test_envelope_validation(factory, alice, bob):
    cid = factory.direct(alice, bob)

    def attempt(**changes):
        env = {"device_id": bob.device_id, "type": "whisper", "body": b64(b"x")}
        env.update(changes.pop("env", {}))
        body = {"id": str(uuid.uuid4()), "conversation_id": cid, "kind": "message", "client_ts": 1, "envelopes": [env]}
        body.update(changes)
        return alice.post("/messages", body)

    assert attempt(env={"body": "not base64!!"}).status_code == 422
    assert attempt(env={"body": "YWJj"[:3]}).status_code == 422  # missing padding
    assert attempt(env={"type": "plain"}).status_code == 422
    assert attempt(env={"body": b64(b"a" * (64 * 1024 + 1))}).status_code == 422
    assert attempt(env={"body": b64(b"a" * (64 * 1024))}).status_code == 201
    assert attempt(id="not-a-uuid").status_code == 422
    assert attempt(kind="shout").status_code == 422
    assert attempt(attachment_ids=[str(uuid.uuid4())] * 11).status_code == 422
    r = alice.post("/messages", {"id": str(uuid.uuid4())})
    assert r.status_code == 422 and r.json()["code"] == "invalid_request"


def test_duplicate_message_is_suppressed(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    mid = str(uuid.uuid4())
    first = alice.send(cid, "once", message_id=mid)
    assert first.status_code == 201
    second = alice.send(cid, "once", message_id=mid)
    assert second.status_code == 200
    assert second.json()["duplicate"] is True and second.json()["message"]["id"] == mid
    assert second.json()["message"]["seq"] == first.json()["message"]["seq"]
    assert len(bob.events("message")) == 1
    with app.state.session_factory() as db:
        assert db.query(Message).filter_by(id=mid).count() == 1
        assert db.query(Event).filter_by(type="message").count() == 1
    # the duplicate is detected even if the envelope set changed meanwhile
    factory.login(bob, "new phone")
    assert alice.send(cid, "once", message_id=mid, envelopes=[]).json()["duplicate"] is True
    # another sender cannot claim the id
    other = bob.send(cid, "mine now", message_id=mid)
    assert other.status_code == 409 and other.json()["code"] == "id_conflict"


def test_concurrent_sends_get_unique_ordered_seqs(factory, alice, bob, carol, client):
    c1 = factory.direct(alice, bob)
    c2 = factory.group(carol, [alice, bob])
    results, errors = [], []

    def worker(actor, cid, n):
        try:
            for i in range(n):
                r = actor.send(cid, f"{actor.username}-{i}")
                assert r.status_code == 201, r.text
                results.append((r.json()["message"]["seq"], r.json()["message"]["id"]))
        except BaseException as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [
        threading.Thread(target=worker, args=(alice, c1, 8)),
        threading.Thread(target=worker, args=(bob, c1, 8)),
        threading.Thread(target=worker, args=(carol, c2, 8)),
        threading.Thread(target=worker, args=(alice, c2, 8)),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)
    assert not errors, errors
    seqs = sorted(s for s, _ in results)
    assert len(seqs) == 32 and len(set(seqs)) == 32
    assert seqs == list(range(seqs[0], seqs[0] + 32))  # no gaps: one monotonic counter
    # a conversation's history is ordered by that seq
    hist = alice.get(f"/conversations/{c1}/messages?limit=200").json()
    assert [m["seq"] for m in hist] == sorted((m["seq"] for m in hist), reverse=True)
    assert len(hist) == 16


def test_offline_sync_pagination_ack_and_receipts(factory, alice, bob):
    cid = factory.direct(alice, bob)
    ids = [alice.send(cid, f"m{i}").json()["message"]["id"] for i in range(25)]
    got, after, pages = [], 0, 0
    while True:
        page = bob.sync(after, limit=10)
        pages += 1
        got += [e["data"]["id"] for e in page["events"] if e["type"] == "message"]
        after = page["next_cursor"]
        if not page["has_more"]:
            break
        assert len(page["events"]) <= 10
    assert got == ids and pages >= 3
    # nothing acknowledged yet: still there
    assert len([e for e in bob.events("message")]) == 25
    # ack only the first 10 messages' worth
    first_page = bob.sync(0, limit=10)
    bob.ack(first_page["next_cursor"])
    remaining = [e["data"]["id"] for e in bob.events("message")]
    assert remaining == ids[len([e for e in first_page["events"] if e["type"] == "message"]) :]
    bob.ack(after)
    assert bob.events("message") == []
    delivered = [i for r in alice.events("receipt") for i in r["data"]["message_ids"]]
    assert sorted(delivered) == sorted(ids)
    assert all(r["data"]["user_id"] == bob.id for r in alice.events("receipt"))


def test_one_delivered_receipt_per_recipient_user(factory, alice, bob, carol):
    bob2 = factory.login(bob, "tablet")
    cid = factory.group(alice, [bob, carol])
    mid = alice.send(cid, "to everyone").json()["message"]["id"]
    bob.ack(bob.sync()["next_cursor"])
    bob2.ack(bob2.sync()["next_cursor"])
    carol.ack(carol.sync()["next_cursor"])
    recs = [r["data"] for r in alice.events("receipt")]
    assert sorted((r["user_id"], tuple(r["message_ids"])) for r in recs) == sorted(
        [(bob.id, (mid,)), (carol.id, (mid,))]
    )


def test_edit_and_delete_authorization(factory, alice, bob):
    cid = factory.direct(alice, bob)
    mid = alice.send(cid, "original").json()["message"]["id"]

    def edit(actor, target, text="edited", kind="edit", **kw):
        return actor.send(cid, text, kind=kind, target_message_id=target, **kw)

    assert edit(bob, mid).status_code == 403
    assert edit(bob, mid, kind="delete", envelopes=[]).status_code == 403
    assert edit(alice, str(uuid.uuid4())).status_code == 404
    assert alice.send(cid, "x", kind="edit").status_code == 422  # target required
    assert alice.send(cid, "x", target_message_id=mid).status_code == 422  # target only for edit/delete

    ok = edit(alice, mid)
    assert ok.status_code == 201 and ok.json()["message"]["kind"] == "edit"
    assert ok.json()["message"]["target_message_id"] == mid
    evs = [e for e in bob.events("message") if e["data"]["kind"] == "edit"]
    assert len(evs) == 1 and evs[0]["data"]["envelope"]["body"]
    assert edit(alice, ok.json()["message"]["id"]).status_code == 422  # cannot edit an edit
    # an edit does not count as unread / new activity
    assert bob.get(f"/conversations/{cid}").json()["unread_count"] == 1

    other_conv = factory.direct(alice, factory.user("dave"))
    foreign = alice.post(
        "/messages",
        {
            "id": str(uuid.uuid4()),
            "conversation_id": other_conv,
            "kind": "edit",
            "target_message_id": mid,
            "client_ts": 1,
            "envelopes": alice.envelopes(other_conv, "x"),
        },
    )
    assert foreign.status_code == 404  # target must be in the same conversation

    # delete: no envelopes allowed; undelivered ciphertext is dropped
    assert edit(alice, mid, kind="delete").status_code == 422  # carries envelopes by default helper
    d = edit(alice, mid, kind="delete", envelopes=[])
    assert d.status_code == 201 and d.json()["message"]["kind"] == "delete"
    page = bob.events("message")
    by_id = {e["data"]["id"]: e["data"] for e in page}
    assert by_id[mid]["deleted"] is True and by_id[mid]["envelope"] is None
    assert [e for e in page if e["data"]["kind"] == "edit"] == []  # undelivered edit of a deleted message
    assert any(e["data"]["kind"] == "delete" and e["data"]["target_message_id"] == mid for e in page)
    assert bob.get(f"/conversations/{cid}").json()["unread_count"] == 0
    assert edit(alice, mid, kind="delete", envelopes=[]).status_code == 409  # already deleted
    hist = {m["id"]: m for m in bob.get(f"/conversations/{cid}/messages").json()}
    assert hist[mid]["deleted"] is True


def test_delete_leaves_no_ciphertext_for_target(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    mid = alice.send(cid, "secret").json()["message"]["id"]
    alice.send(cid, "", kind="delete", target_message_id=mid, envelopes=[])
    with app.state.session_factory() as db:
        assert db.query(MessageEnvelope).filter_by(message_id=mid).count() == 0


def test_history_pagination_and_envelope_visibility(factory, alice, bob):
    cid = factory.direct(alice, bob)
    ids = [alice.send(cid, f"m{i}").json()["message"] for i in range(7)]
    page1 = bob.get(f"/conversations/{cid}/messages?limit=3").json()
    assert [m["id"] for m in page1] == [m["id"] for m in reversed(ids[-3:])]
    assert page1[0]["envelope"] is not None  # undelivered: ciphertext for bob's device
    page2 = bob.get(f"/conversations/{cid}/messages?limit=3&before_seq={page1[-1]['seq']}").json()
    assert [m["id"] for m in page2] == [m["id"] for m in reversed(ids[1:4])]
    sender_view = alice.get(f"/conversations/{cid}/messages?limit=2").json()
    assert all(m["envelope"] is None for m in sender_view)  # sender has no envelope for its own device
    bob.ack(bob.sync()["next_cursor"])
    assert all(m["envelope"] is None for m in bob.get(f"/conversations/{cid}/messages").json())
    assert bob.get(f"/conversations/{cid}/messages?limit=201").status_code == 422
    assert bob.get(f"/conversations/{cid}/messages?limit=0").status_code == 422


def test_read_receipts_and_unread_count(factory, alice, bob):
    cid = factory.direct(alice, bob)
    seqs = [alice.send(cid, f"m{i}").json()["message"]["seq"] for i in range(4)]
    bob.send(cid, "mine")  # own messages are never unread
    conv = bob.get(f"/conversations/{cid}").json()
    assert conv["unread_count"] == 4 and conv["my_read_seq"] == 0
    assert conv["last_message_seq"] > seqs[-1]
    assert alice.get("/conversations").json()[0]["unread_count"] == 1
    cursor = alice.sync()["next_cursor"]

    assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": seqs[1]}).status_code == 204
    conv = bob.get(f"/conversations/{cid}").json()
    assert conv["my_read_seq"] == seqs[1] and conv["unread_count"] == 2
    (rec,) = alice.events("receipt", after=cursor)
    assert rec["data"] == {"conversation_id": cid, "user_id": bob.id, "type": "read", "up_to_seq": seqs[1]}
    # the pointer never moves back and no event is sent for a no-op
    cursor = alice.sync(cursor)["next_cursor"]
    assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": 1}).status_code == 204
    assert bob.get(f"/conversations/{cid}").json()["my_read_seq"] == seqs[1]
    assert alice.events("receipt", after=cursor) == []
    # reading beyond the latest message is clamped
    assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": 10**12}).status_code == 204
    conv = bob.get(f"/conversations/{cid}").json()
    assert conv["unread_count"] == 0 and conv["my_read_seq"] == conv["last_message_seq"]
    assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": -1}).status_code == 422


def test_ciphertext_only_gets_to_the_addressed_device(factory, alice, bob, carol):
    cid = factory.group(alice, [bob, carol])
    alice.send(cid, "for-everyone")
    bob_env = base64.b64decode(bob.events("message")[0]["data"]["envelope"]["body"])
    carol_env = base64.b64decode(carol.events("message")[0]["data"]["envelope"]["body"])
    assert bob_env != carol_env and bob.device_id.encode() in bob_env and carol.device_id.encode() in carol_env


def test_oversized_json_body_rejected(alice):
    r = alice.post("/messages", {"id": "x" * (9 * 1024 * 1024)})
    assert r.status_code == 413 and r.json()["code"] == "payload_too_large"

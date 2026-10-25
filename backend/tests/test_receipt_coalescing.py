"""Queued receipts fold together per (conversation, user, type) for a device that has not acknowledged them, and
the silent read (`notify: false`). Cursor semantics must not change: a merged receipt is a new, higher seq."""


def receipts(actor, after=0, limit=200):
    return [e for e in actor.sync(after, limit)["events"] if e["type"] == "receipt"]


def send_n(alice, cid, n):
    return [alice.send(cid, f"m{i}").json()["message"] for i in range(n)]


def test_read_receipts_keep_only_the_highest_seq(factory, alice, bob):
    cid = factory.direct(alice, bob)
    msgs = send_n(alice, cid, 4)
    for m in msgs:
        assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": m["seq"]}).status_code == 204
    got = [e for e in receipts(alice) if e["data"]["type"] == "read"]
    assert len(got) == 1
    assert got[0]["data"] == {
        "conversation_id": cid,
        "user_id": bob.id,
        "type": "read",
        "up_to_seq": msgs[-1]["seq"],
    }


def test_delivered_receipts_merge_message_ids_in_order(factory, alice, bob):
    cid = factory.direct(alice, bob)
    ids = [m["id"] for m in send_n(alice, cid, 3)]
    # bob acknowledges after every message: three delivery batches
    bob_events = [e for e in bob.sync(0)["events"] if e["type"] == "message"]
    for e in bob_events:
        bob.ack(e["seq"])
    got = [e for e in receipts(alice) if e["data"]["type"] == "delivered"]
    assert len(got) == 1
    assert got[0]["data"]["message_ids"] == ids


def test_receipts_of_other_users_types_and_conversations_stay_separate(factory, alice, bob, carol):
    gid = factory.group(alice, [bob, carol])
    other = factory.direct(alice, bob)
    m = alice.send(gid, "hi").json()["message"]
    o = alice.send(other, "hi").json()["message"]
    bob.post(f"/conversations/{gid}/read", {"up_to_seq": m["seq"]})
    carol.post(f"/conversations/{gid}/read", {"up_to_seq": m["seq"]})
    bob.post(f"/conversations/{other}/read", {"up_to_seq": o["seq"]})
    bob.ack(bob.sync()["next_cursor"])
    carol.ack(carol.sync()["next_cursor"])
    got = receipts(alice)
    keys = sorted((e["data"]["user_id"], e["data"]["type"], e["conversation_id"]) for e in got)
    assert keys == sorted(
        [
            (bob.id, "read", gid),
            (carol.id, "read", gid),
            (bob.id, "read", other),
            (bob.id, "delivered", gid),
            (carol.id, "delivered", gid),
            (bob.id, "delivered", other),
        ]
    )


def test_a_device_that_already_fetched_a_receipt_gets_the_merged_one_after_its_cursor(factory, alice, bob):
    cid = factory.direct(alice, bob)
    msgs = send_n(alice, cid, 3)
    bob.post(f"/conversations/{cid}/read", {"up_to_seq": msgs[0]["seq"]})
    page = alice.sync()
    cursor = page["next_cursor"]  # alice fetched the first read receipt but has not acknowledged it
    first = [e for e in page["events"] if e["type"] == "receipt" and e["data"]["type"] == "read"]
    assert first[0]["data"]["up_to_seq"] == msgs[0]["seq"]
    bob.post(f"/conversations/{cid}/read", {"up_to_seq": msgs[2]["seq"]})
    again = [e for e in alice.sync(cursor)["events"] if e["type"] == "receipt"]
    assert len(again) == 1 and again[0]["seq"] > cursor
    assert again[0]["data"]["up_to_seq"] == msgs[2]["seq"]
    # from the start the stale row is gone: one read receipt only
    assert len([e for e in receipts(alice) if e["data"]["type"] == "read"]) == 1


def test_acknowledged_receipts_are_not_merged_into_new_ones(factory, alice, bob):
    cid = factory.direct(alice, bob)
    msgs = send_n(alice, cid, 2)
    bob.post(f"/conversations/{cid}/read", {"up_to_seq": msgs[0]["seq"]})
    alice.ack(alice.sync()["next_cursor"])
    bob.post(f"/conversations/{cid}/read", {"up_to_seq": msgs[1]["seq"]})
    got = [e for e in receipts(alice) if e["data"]["type"] == "read"]
    assert [e["data"]["up_to_seq"] for e in got] == [msgs[1]["seq"]]


def test_coalescing_keeps_paging_and_ack_correct(factory, alice, bob):
    cid = factory.direct(alice, bob)
    msgs = send_n(alice, cid, 6)
    for m in msgs:
        bob.post(f"/conversations/{cid}/read", {"up_to_seq": m["seq"]})
    seen, after = [], 0
    while True:
        page = alice.sync(after, limit=1)
        seen += page["events"]
        assert page["next_cursor"] >= after
        after = page["next_cursor"]
        if not page["has_more"]:
            break
    seqs = [e["seq"] for e in seen]
    assert seqs == sorted(set(seqs))
    assert [e for e in seen if e["type"] == "receipt" and e["data"]["type"] == "read"][-1]["data"]["up_to_seq"] == msgs[
        -1
    ]["seq"]
    alice.ack(after)
    assert alice.sync()["events"] == []


def test_own_devices_also_get_one_merged_read_receipt(factory, alice, bob):
    bob2 = factory.login(bob, "tablet")
    cid = factory.direct(alice, bob)
    msgs = send_n(alice, cid, 3)
    bob2.ack(bob2.sync()["next_cursor"])
    for m in msgs:
        bob.post(f"/conversations/{cid}/read", {"up_to_seq": m["seq"]})
    got = [e for e in receipts(bob2) if e["data"]["type"] == "read"]
    assert len(got) == 1 and got[0]["data"]["up_to_seq"] == msgs[-1]["seq"]


# ---- silent read (notify: false)


def test_silent_read_moves_the_pointer_but_tells_only_own_devices(factory, alice, bob):
    bob2 = factory.login(bob, "tablet")
    cid = factory.direct(alice, bob)
    m = alice.send(cid, "hello").json()["message"]
    alice.ack(alice.sync()["next_cursor"])
    bob2.ack(bob2.sync()["next_cursor"])
    r = bob.post(f"/conversations/{cid}/read", {"up_to_seq": m["seq"], "notify": False})
    assert r.status_code == 204
    assert bob.get(f"/conversations/{cid}").json()["my_read_seq"] == m["seq"]
    assert [e for e in receipts(alice) if e["data"]["type"] == "read"] == []
    own = [e for e in receipts(bob2) if e["data"]["type"] == "read"]
    assert len(own) == 1 and own[0]["data"]["up_to_seq"] == m["seq"] and own[0]["data"]["user_id"] == bob.id


def test_read_notifies_by_default_and_with_notify_true(factory, alice, bob):
    cid = factory.direct(alice, bob)
    a, b = send_n(alice, cid, 2)
    alice.ack(alice.sync()["next_cursor"])
    assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": a["seq"]}).status_code == 204
    assert len([e for e in receipts(alice) if e["data"]["type"] == "read"]) == 1
    assert bob.post(f"/conversations/{cid}/read", {"up_to_seq": b["seq"], "notify": True}).status_code == 204
    got = [e for e in receipts(alice) if e["data"]["type"] == "read"]
    assert len(got) == 1 and got[0]["data"]["up_to_seq"] == b["seq"]


def test_notify_must_be_a_boolean(factory, alice, bob):
    cid = factory.direct(alice, bob)
    r = bob.post(f"/conversations/{cid}/read", {"up_to_seq": 0, "notify": "maybe"})
    assert r.status_code == 422

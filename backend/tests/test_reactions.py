"""Reactions: kind "reaction" on any member's message, encrypted for the full device set, no unread/last message."""

import base64
import uuid

from sqlalchemy import select

from app.models import Message, MessageEnvelope


def react(actor, cid, target_id, emoji="thumbs-up", **extra):
    return actor.send(cid, emoji, kind="reaction", target_message_id=target_id, **extra)


def test_reaction_on_a_message_of_someone_else(factory, alice, bob):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "lunch?").json()["message"]
    bob.ack(bob.sync()["next_cursor"])
    r = react(bob, cid, target["id"])
    assert r.status_code == 201, r.text
    msg = r.json()["message"]
    assert msg["kind"] == "reaction" and msg["target_message_id"] == target["id"]
    assert msg["sender_user_id"] == bob.id and msg["attachment_ids"] == [] and msg["deleted"] is False
    (ev,) = [e for e in alice.events("message") if e["data"]["kind"] == "reaction"]
    assert ev["data"]["id"] == msg["id"] and ev["conversation_id"] == cid
    body = base64.b64decode(ev["data"]["envelope"]["body"])
    assert body == f"ct|thumbs-up|{alice.device_id}".encode()  # the server only relays opaque ciphertext
    # bob's own devices: none other here, and the sender never gets a copy
    assert bob.events("message") == []


def test_reaction_on_own_message_and_to_every_device(factory, alice, bob):
    alice2 = factory.login(alice, "tablet")
    bob2 = factory.login(bob, "tablet")
    cid = factory.direct(alice, bob)
    mine = alice.send(cid, "mine").json()["message"]
    r = react(alice, cid, mine["id"])
    assert r.status_code == 201
    for dev in (alice2, bob, bob2):
        (ev,) = [e for e in dev.events("message") if e["data"]["kind"] == "reaction"]
        assert base64.b64decode(ev["data"]["envelope"]["body"]).endswith(dev.device_id.encode())


def test_reaction_needs_the_full_device_set(factory, alice, bob):
    cid = factory.direct(alice, bob)
    target = bob.send(cid, "x").json()["message"]
    r = react(alice, cid, target["id"], envelopes=[])
    assert r.status_code == 409 and r.json()["code"] == "mismatched_devices"
    assert [d["device_id"] for d in r.json()["missing_devices"]] == [bob.device_id]


def test_reaction_validation(factory, alice, bob, carol):
    cid = factory.direct(alice, bob)
    other = factory.direct(bob, carol)
    target = bob.send(cid, "x").json()["message"]
    foreign = bob.send(other, "elsewhere").json()["message"]
    # target is required, and only reactions/edits/deletes may have one
    assert alice.send(cid, "x", kind="reaction").status_code == 422
    assert alice.send(cid, "x", target_message_id=target["id"]).status_code == 422
    # unknown target and a target from another conversation look the same
    assert react(alice, cid, str(uuid.uuid4())).status_code == 404
    assert react(alice, cid, foreign["id"]).status_code == 404
    # no attachments
    att = alice.post(
        "/attachments?purpose=message", content=b"abc", headers={"Content-Type": "application/octet-stream"}
    ).json()
    assert react(alice, cid, target["id"], attachment_ids=[att["id"]]).status_code == 422
    # a non-member cannot react at all
    body = {
        "id": str(uuid.uuid4()),
        "conversation_id": cid,
        "kind": "reaction",
        "target_message_id": target["id"],
        "client_ts": 1,
        "envelopes": [],
    }
    assert carol.post("/messages", body).status_code == 404


def test_cannot_react_to_edits_deletes_reactions_or_deleted_messages(factory, alice, bob):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "orig").json()["message"]
    edit = alice.send(cid, "new", kind="edit", target_message_id=target["id"]).json()["message"]
    reaction = react(bob, cid, target["id"]).json()["message"]
    for non_message in (edit, reaction):
        assert react(bob, cid, non_message["id"]).status_code == 422
    assert alice.send(cid, "gone", kind="delete", target_message_id=target["id"]).status_code == 201
    r = react(bob, cid, target["id"])
    assert r.status_code == 409


def test_reaction_does_not_touch_last_message_or_unread(factory, alice, bob):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "hello").json()["message"]
    before = bob.get(f"/conversations/{cid}").json()
    assert before["unread_count"] == 1
    react(alice, cid, target["id"])
    react(bob, cid, target["id"])
    after = bob.get(f"/conversations/{cid}").json()
    assert after["unread_count"] == 1
    for key in ("last_message_at", "last_message_seq", "updated_at"):
        assert after[key] == before[key], key
    assert alice.get(f"/conversations/{cid}").json()["unread_count"] == 0


def test_reaction_is_idempotent(factory, alice, bob):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "hello").json()["message"]
    mid = str(uuid.uuid4())
    assert react(bob, cid, target["id"], message_id=mid).status_code == 201
    again = react(bob, cid, target["id"], message_id=mid)
    assert again.status_code == 200 and again.json()["duplicate"] is True
    assert len([e for e in alice.events("message") if e["data"]["kind"] == "reaction"]) == 1


def test_history_includes_reactions(factory, alice, bob):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "hello").json()["message"]
    reaction = react(bob, cid, target["id"]).json()["message"]
    hist = alice.get(f"/conversations/{cid}/messages").json()
    kinds = {m["id"]: m["kind"] for m in hist}
    assert kinds == {target["id"]: "message", reaction["id"]: "reaction"}
    mine = next(m for m in hist if m["id"] == reaction["id"])
    assert mine["target_message_id"] == target["id"] and mine["envelope"] is not None  # alice's undelivered copy


def test_deleting_the_target_drops_undelivered_reactions(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "regret").json()["message"]
    bob.ack(bob.sync()["next_cursor"])
    reaction = react(bob, cid, target["id"]).json()["message"]
    own = react(alice, cid, target["id"]).json()["message"]
    assert alice.send(cid, "x", kind="delete", target_message_id=target["id"]).status_code == 201
    with app.state.session_factory() as db:
        left = db.scalars(
            select(MessageEnvelope).where(MessageEnvelope.message_id.in_([reaction["id"], own["id"]]))
        ).all()
        assert left == []
        assert db.get(Message, reaction["id"]) is not None  # the row stays, only the ciphertext goes
    # alice has not fetched bob's reaction yet; it is skipped, not delivered without ciphertext
    msgs = [e["data"] for e in alice.events("message")]
    assert [m["kind"] for m in msgs] == []
    # history still lists it, without an envelope
    hist = {m["id"]: m for m in alice.get(f"/conversations/{cid}/messages").json()}
    assert hist[reaction["id"]]["envelope"] is None


def test_delivered_reactions_keep_their_ciphertext_after_the_target_is_deleted(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    target = alice.send(cid, "x").json()["message"]
    reaction = react(bob, cid, target["id"]).json()["message"]
    alice.ack(alice.sync()["next_cursor"])  # alice has fetched and acked it
    alice.send(cid, "x", kind="delete", target_message_id=target["id"])
    with app.state.session_factory() as db:
        env = db.get(MessageEnvelope, (reaction["id"], alice.device_id))
        assert env is not None and env.delivered_at is not None


def test_reactions_work_in_groups_and_reach_every_member(factory, alice, bob, carol):
    gid = factory.group(alice, [bob, carol])
    target = alice.send(gid, "party").json()["message"]
    assert react(carol, gid, target["id"]).status_code == 201
    for dev in (alice, bob):
        assert [e["data"]["kind"] for e in dev.events("message")][-1] == "reaction"

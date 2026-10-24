"""Server side disappearing messages: the conversation timer, per-message expiry, and the pruning job.

The timer column only drives pruning on the server. Clients take the timer from encrypted content (see
docs/SECURITY.md), so nothing here may be needed to keep a phone's own history intact."""

import pytest
from sqlalchemy import func, select, update

from app import clock
from app.models import Attachment, DeliveryReceipt, Message, MessageAttachment, MessageEnvelope
from app.services.maintenance import prune

MIN, MAX = 300, 365 * 24 * 3600


def set_timer(actor, cid, seconds):
    return actor.patch(f"/conversations/{cid}", {"disappearing_seconds": seconds})


def make_old(app, message_id, ms_ago=1000):
    with app.state.session_factory() as db:
        db.execute(update(Message).where(Message.id == message_id).values(expires_at=clock.now_ms() - ms_ago))
        db.commit()


def run_prune(app):
    with app.state.session_factory() as db:
        return prune(db, app.state.settings)


def count(app, model, *where):
    with app.state.session_factory() as db:
        return db.scalar(select(func.count()).select_from(model).where(*where))


def test_default_is_off_and_objects_expose_the_fields(factory, alice, bob):
    cid = factory.direct(alice, bob)
    assert alice.get(f"/conversations/{cid}").json()["disappearing_seconds"] is None
    msg = alice.send(cid, "x").json()["message"]
    assert msg["expires_at"] is None
    assert alice.get("/conversations").json()[0]["disappearing_seconds"] is None


@pytest.mark.parametrize("seconds", [MIN, 3600, 86400, MAX])
def test_any_member_of_a_direct_chat_can_set_it(factory, alice, bob, seconds):
    cid = factory.direct(alice, bob)
    r = set_timer(bob, cid, seconds)
    assert r.status_code == 200 and r.json()["disappearing_seconds"] == seconds
    assert alice.get(f"/conversations/{cid}").json()["disappearing_seconds"] == seconds
    assert set_timer(alice, cid, None).json()["disappearing_seconds"] is None  # off again


@pytest.mark.parametrize("bad", [0, 1, 299, -300, MAX + 1, 10**12, "abc", 300.5, True, [300], {"a": 1}])
def test_values_outside_null_or_300_to_one_year_are_rejected(factory, alice, bob, bad):
    cid = factory.direct(alice, bob)
    assert set_timer(alice, cid, bad).status_code == 422
    assert alice.get(f"/conversations/{cid}").json()["disappearing_seconds"] is None


def test_change_emits_conversation_updated_to_every_member_device(factory, alice, bob):
    bob2 = factory.login(bob, "tablet")
    cid = factory.direct(alice, bob)
    before = bob.get(f"/conversations/{cid}").json()["updated_at"]
    for dev in (alice, bob, bob2):
        dev.ack(dev.sync()["next_cursor"])
    assert set_timer(alice, cid, 600).status_code == 200
    for dev in (alice, bob, bob2):
        (ev,) = dev.events("conversation_updated")
        assert ev["conversation_id"] == cid and ev["data"]["disappearing_seconds"] == 600
        assert ev["data"]["updated_at"] >= before


def test_group_changes_need_an_admin(factory, alice, bob, carol):
    gid = factory.group(alice, [bob])
    assert set_timer(bob, gid, 600).status_code == 403
    assert set_timer(carol, gid, 600).status_code == 404  # not a member: no existence oracle
    assert set_timer(alice, gid, 600).json()["disappearing_seconds"] == 600
    assert alice.patch(f"/conversations/{gid}/members/{bob.id}", {"role": "admin"}).status_code == 200
    assert set_timer(bob, gid, 900).json()["disappearing_seconds"] == 900


def test_direct_chats_still_refuse_name_and_avatar_changes(factory, alice, bob):
    cid = factory.direct(alice, bob)
    assert alice.patch(f"/conversations/{cid}", {"name": "nope"}).status_code == 409
    assert alice.patch(f"/conversations/{cid}", {"name": "nope", "disappearing_seconds": 600}).status_code == 409
    assert alice.patch(f"/conversations/{cid}", {}).status_code == 409
    assert alice.get(f"/conversations/{cid}").json()["disappearing_seconds"] is None


def test_other_patches_leave_the_timer_alone(factory, alice, bob):
    gid = factory.group(alice, [bob])
    set_timer(alice, gid, 600)
    assert alice.patch(f"/conversations/{gid}", {"name": "Renamed"}).json()["disappearing_seconds"] == 600
    assert set_timer(alice, gid, None).json()["disappearing_seconds"] is None


def test_expires_at_is_fixed_when_the_message_is_stored(factory, alice, bob):
    cid = factory.direct(alice, bob)
    before = clock.now_ms()
    set_timer(alice, cid, 600)
    first = alice.send(cid, "a").json()["message"]
    assert first["expires_at"] == first["server_ts"] + 600_000 and first["server_ts"] >= before
    set_timer(alice, cid, 3600)
    second = alice.send(cid, "b").json()["message"]
    assert second["expires_at"] == second["server_ts"] + 3_600_000
    set_timer(alice, cid, None)
    third = alice.send(cid, "c").json()["message"]
    assert third["expires_at"] is None
    hist = {m["id"]: m for m in bob.get(f"/conversations/{cid}/messages").json()}  # history shows the stored values
    assert hist[first["id"]]["expires_at"] == first["expires_at"] and hist[third["id"]]["expires_at"] is None
    synced = {e["data"]["id"]: e["data"] for e in bob.events("message")}
    assert synced[second["id"]]["expires_at"] == second["expires_at"]


def test_edits_deletes_and_reactions_get_an_expiry_too(factory, alice, bob):
    cid = factory.direct(alice, bob)
    set_timer(alice, cid, 600)
    target = alice.send(cid, "t").json()["message"]
    edit = alice.send(cid, "t2", kind="edit", target_message_id=target["id"]).json()["message"]
    reaction = bob.send(cid, "r", kind="reaction", target_message_id=target["id"]).json()["message"]
    for m in (edit, reaction):
        assert m["expires_at"] == m["server_ts"] + 600_000


def test_prune_deletes_expired_messages_with_envelopes_attachment_links_and_receipts(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    set_timer(alice, cid, 600)
    att = alice.post(
        "/attachments?purpose=message", content=b"secret bytes", headers={"Content-Type": "application/octet-stream"}
    ).json()
    old = alice.send(cid, "old", attachment_ids=[att["id"]]).json()["message"]
    fresh = alice.send(cid, "fresh").json()["message"]
    bob.ack(bob.sync()["next_cursor"])  # a delivery receipt row exists for both
    assert count(app, DeliveryReceipt, DeliveryReceipt.message_id == old["id"]) == 1

    assert run_prune(app)["messages"] == 0  # nothing has expired yet
    make_old(app, old["id"])
    path = app.state.settings.attachments_dir / att["id"][:2] / att["id"]
    assert path.exists()
    result = run_prune(app)
    assert result["messages"] == 1

    assert count(app, Message, Message.id == old["id"]) == 0
    assert count(app, MessageEnvelope, MessageEnvelope.message_id == old["id"]) == 0
    assert count(app, MessageAttachment, MessageAttachment.message_id == old["id"]) == 0
    assert count(app, DeliveryReceipt, DeliveryReceipt.message_id == old["id"]) == 0
    assert count(app, Message, Message.id == fresh["id"]) == 1
    assert count(app, MessageEnvelope, MessageEnvelope.message_id == fresh["id"]) == 1
    ids = [m["id"] for m in alice.get(f"/conversations/{cid}/messages").json()]
    assert ids == [fresh["id"]]
    # the attachment is now unreferenced; the garbage collector takes it once it is older than 24 h
    assert count(app, Attachment, Attachment.id == att["id"]) == 1 and path.exists()
    with app.state.session_factory() as db:
        db.execute(update(Attachment).values(created_at=clock.now_ms() - 25 * 3600 * 1000))
        db.commit()
    assert run_prune(app)["attachments"] == 1
    assert not path.exists() and count(app, Attachment) == 0


def test_prune_drops_edits_and_reactions_of_an_expired_message_and_undelivered_events_vanish(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    set_timer(alice, cid, 600)
    target = alice.send(cid, "t").json()["message"]
    edit = alice.send(cid, "t2", kind="edit", target_message_id=target["id"]).json()["message"]
    reaction = bob.send(cid, "r", kind="reaction", target_message_id=target["id"]).json()["message"]
    keep = alice.send(cid, "keep").json()["message"]
    make_old(app, target["id"])  # only the target is old: its edit and reaction go with it
    assert run_prune(app)["messages"] == 3
    with app.state.session_factory() as db:
        left = set(db.scalars(select(Message.id)))
    assert left == {keep["id"]} and edit["id"] not in left and reaction["id"] not in left
    # bob never synced: events for removed messages are skipped, the rest still arrives
    got = [e["data"]["id"] for e in bob.events("message")]
    assert got == [keep["id"]]


def test_conversations_without_a_timer_are_never_pruned(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    msg = alice.send(cid, "forever").json()["message"]
    with app.state.session_factory() as db:
        assert db.get(Message, msg["id"]).expires_at is None
    assert run_prune(app)["messages"] == 0
    assert count(app, Message) == 1


def test_pruning_keeps_the_conversation_usable(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    set_timer(alice, cid, 600)
    only = alice.send(cid, "only").json()["message"]
    make_old(app, only["id"])
    run_prune(app)
    conv = bob.get(f"/conversations/{cid}").json()
    assert conv["unread_count"] == 0 and conv["disappearing_seconds"] == 600
    assert bob.send(cid, "still works").status_code == 201
    assert alice.post(f"/conversations/{cid}/read", {"up_to_seq": 10**9}).status_code == 204

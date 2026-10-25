"""Sending messages: device-set check, idempotency, monotonic seq, per-device events."""

import base64

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from app import clock
from app.deps import Principal
from app.errors import ApiError, conflict, forbidden, invalid, not_found
from app.models import (
    Attachment,
    Conversation,
    Counter,
    Device,
    Message,
    MessageAttachment,
    MessageEnvelope,
)
from app.schemas import SendMessage
from app.services import conversations as convs
from app.services.events import conversation_member_ids, emit, keyed_device_rows
from app.services.views import messages_out

SEQ_COUNTER = "message_seq"


def next_seq(db: Session) -> int:
    """Monotonic message seq. The UPDATE takes the row lock first, so concurrent senders serialise
    (SQLite: BEGIN IMMEDIATE; PostgreSQL: row lock) and every transaction sees a distinct value."""
    res = db.execute(update(Counter).where(Counter.name == SEQ_COUNTER).values(value=Counter.value + 1))
    if res.rowcount == 0:  # table created without the migration seed
        db.add(Counter(name=SEQ_COUNTER, value=1))
        db.flush()
        return 1
    return db.scalar(select(Counter.value).where(Counter.name == SEQ_COUNTER))


def _check_device_set(
    db: Session, member_ids: list[str], sender_device_id: str, req: SendMessage
) -> list[tuple[str, str]]:
    required = {did: uid for did, uid in keyed_device_rows(db, member_ids) if did != sender_device_id}
    given = [e.device_id for e in req.envelopes]
    if len(set(given)) != len(given):
        raise invalid("duplicate device in envelopes")
    missing = [d for d in required if d not in set(given)]
    extra = [d for d in given if d not in required]
    if missing or extra:
        owners = dict(db.execute(select(Device.id, Device.user_id).where(Device.id.in_(extra))).all()) if extra else {}
        raise ApiError(
            409,
            "mismatched_devices",
            "envelope devices do not match the conversation's current devices",
            {
                "missing_devices": [{"user_id": required[d], "device_id": d} for d in missing],
                "extra_devices": [{"user_id": owners.get(d), "device_id": d} for d in extra],
            },
        )
    return list(required.items())


def send(db: Session, p: Principal, req: SendMessage) -> tuple[dict, bool]:
    conv, _member = convs.require_member(db, req.conversation_id, p.user_id)

    existing = db.get(Message, req.id)
    if existing is not None:
        if existing.sender_user_id != p.user_id:
            raise conflict("message id already used", "id_conflict")
        return messages_out(db, [existing], None)[0], True

    if req.kind == "call":
        if conv.type != "direct":
            raise conflict("calls are only available in direct conversations", "calls_direct_only")
        if req.target_message_id:
            raise invalid("a call carries no target_message_id")
        if req.attachment_ids:
            raise invalid("a call carries no attachments")
    elif req.kind == "message" and req.target_message_id:
        raise invalid("target_message_id is only for edit, delete and reaction")
    elif req.kind != "message" and not req.target_message_id:
        raise invalid("target_message_id is required for edit, delete and reaction")
    if req.kind in ("delete", "reaction") and req.attachment_ids:
        raise invalid(f"a {req.kind} carries no attachments")

    target = None
    if req.target_message_id:
        target = db.get(Message, req.target_message_id)
        if target is None or target.conversation_id != conv.id:
            raise not_found("target message not found")
        # Edits and deletes are the author's; a reaction may name any member's message.
        if req.kind != "reaction" and target.sender_user_id != p.user_id:
            raise forbidden("you can only change your own messages")
        if target.kind != "message":
            raise invalid("target must be a regular message")
        if target.deleted_at is not None:
            raise conflict("target message already deleted")

    member_ids = conversation_member_ids(db, conv.id)
    # Deletes carry encrypted envelopes too, so recipients can authenticate them (the server cannot forge one).
    recipients = _check_device_set(db, member_ids, p.device_id, req)

    if req.attachment_ids:
        found = {a.id: a for a in db.scalars(select(Attachment).where(Attachment.id.in_(req.attachment_ids)))}
        for aid in req.attachment_ids:
            a = found.get(aid)
            if a is None or a.purpose != "message" or a.uploader_id != p.user_id:
                raise invalid("attachment_ids must be your own uploaded message attachments")

    now = clock.now_ms()
    msg = Message(
        id=req.id,
        seq=next_seq(db),
        conversation_id=conv.id,
        sender_user_id=p.user_id,
        sender_device_id=p.device_id,
        kind=req.kind,
        target_message_id=req.target_message_id,
        client_ts=req.client_ts,
        server_ts=now,
        # The conversation's timer as it is right now; later changes do not touch messages already stored.
        # Calls ignore the timer: the maintenance job removes them after FAMILYCHAT_CALL_RETENTION_SECONDS.
        expires_at=now + conv.disappearing_seconds * 1000 if conv.disappearing_seconds and req.kind != "call" else None,
    )
    db.add(msg)
    db.flush()
    for aid in dict.fromkeys(req.attachment_ids):
        db.add(MessageAttachment(message_id=msg.id, attachment_id=aid))
    for e in req.envelopes:
        db.add(MessageEnvelope(message_id=msg.id, device_id=e.device_id, type=e.type, body=base64.b64decode(e.body)))

    if req.kind == "message":
        conv.last_message_at = now
        conv.last_message_seq = msg.seq
        conv.updated_at = now
    elif req.kind == "edit":
        target.edited_at = now
    elif req.kind == "delete":
        target.deleted_at = now
        # Drop ciphertext nobody has fetched yet (the target, edits of it and reactions to it).
        derived_ids = select(Message.id).where(
            Message.target_message_id == target.id, Message.kind.in_(("edit", "reaction"))
        )
        db.execute(
            delete(MessageEnvelope).where(
                MessageEnvelope.delivered_at.is_(None),
                (MessageEnvelope.message_id == target.id) | MessageEnvelope.message_id.in_(derived_ids),
            )
        )
    # kinds "reaction" and "call": nothing else changes; they are not messages for last_message_* or unread counts.
    db.flush()

    emit(db, [d for d, _u in recipients], "message", {"message_id": msg.id}, conv.id, urgent=req.kind == "call")
    return messages_out(db, [msg], None)[0], False


def list_messages(
    db: Session, conv: Conversation, user_id: str, device_id: str, before_seq: int | None, limit: int
) -> list[dict]:
    member = convs.get_membership(db, conv.id, user_id)
    q = select(Message).where(
        Message.conversation_id == conv.id, Message.server_ts >= member.joined_at, Message.kind != "call"
    )
    if before_seq is not None:
        q = q.where(Message.seq < before_seq)
    msgs = db.scalars(q.order_by(Message.seq.desc()).limit(limit)).all()
    return messages_out(db, list(msgs), device_id)

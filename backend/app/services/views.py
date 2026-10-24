"""Model -> API object serialisation."""

import base64
from collections.abc import Iterable

from sqlalchemy import and_, func, select
from sqlalchemy.orm import Session

from app.models import (
    Conversation,
    ConversationMember,
    Device,
    DeviceKey,
    Message,
    MessageAttachment,
    MessageEnvelope,
    User,
)


def user_out(u: User) -> dict:
    return {
        "id": u.id,
        "username": u.username,
        "display_name": u.display_name,
        "avatar_attachment_id": u.avatar_attachment_id,
        "is_admin": bool(u.is_admin),
        "created_at": u.created_at,
    }


def device_out(d: Device, identity_key: str | None) -> dict:
    return {
        "id": d.id,
        "user_id": d.user_id,
        "number": d.number,
        "name": d.name,
        "created_at": d.created_at,
        "last_seen_at": d.last_seen_at,
        "identity_key": identity_key,
    }


def device_out_db(db: Session, d: Device) -> dict:
    key = db.scalar(select(DeviceKey.identity_key).where(DeviceKey.device_id == d.id))
    return device_out(d, key)


def devices_out_db(db: Session, devices: list[Device]) -> list[dict]:
    keys = (
        dict(
            db.execute(
                select(DeviceKey.device_id, DeviceKey.identity_key).where(
                    DeviceKey.device_id.in_([d.id for d in devices])
                )
            ).all()
        )
        if devices
        else {}
    )
    return [device_out(d, keys.get(d.id)) for d in devices]


def conversations_out(db: Session, conversation_ids: Iterable[str], viewer_id: str) -> list[dict]:
    """Conversation objects as seen by `viewer_id`, newest activity first. Non-member ids are skipped."""
    ids = list(conversation_ids)
    if not ids:
        return []
    convs = db.scalars(select(Conversation).where(Conversation.id.in_(ids))).all()
    members = db.scalars(
        select(ConversationMember)
        .where(ConversationMember.conversation_id.in_(ids))
        .order_by(ConversationMember.joined_at, ConversationMember.user_id)
    ).all()
    by_conv: dict[str, list[ConversationMember]] = {}
    for m in members:
        by_conv.setdefault(m.conversation_id, []).append(m)
    unread = dict(
        db.execute(
            select(Message.conversation_id, func.count(Message.id))
            .join(
                ConversationMember,
                and_(
                    ConversationMember.conversation_id == Message.conversation_id,
                    ConversationMember.user_id == viewer_id,
                ),
            )
            .where(
                Message.conversation_id.in_(ids),
                Message.kind == "message",
                Message.deleted_at.is_(None),
                Message.sender_user_id != viewer_id,
                Message.seq > ConversationMember.read_seq,
            )
            .group_by(Message.conversation_id)
        ).all()
    )
    out = []
    for c in convs:
        ms = by_conv.get(c.id, [])
        mine = next((m for m in ms if m.user_id == viewer_id), None)
        if mine is None:
            continue
        out.append(
            {
                "id": c.id,
                "type": c.type,
                "name": c.name,
                "avatar_attachment_id": c.avatar_attachment_id,
                "created_by": c.created_by,
                "created_at": c.created_at,
                "updated_at": c.updated_at,
                "last_message_at": c.last_message_at,
                "last_message_seq": c.last_message_seq,
                "my_read_seq": mine.read_seq,
                "unread_count": unread.get(c.id, 0),
                "members": [{"user_id": m.user_id, "role": m.role, "joined_at": m.joined_at} for m in ms],
            }
        )
    out.sort(key=lambda c: (c["last_message_at"] or c["updated_at"], c["created_at"]), reverse=True)
    return out


def messages_out(db: Session, messages: list[Message], device_id: str | None) -> list[dict]:
    """Message objects with `device_id`'s undelivered envelope (null when none / already delivered)."""
    if not messages:
        return []
    ids = [m.id for m in messages]
    atts: dict[str, list[str]] = {}
    for mid, aid in db.execute(
        select(MessageAttachment.message_id, MessageAttachment.attachment_id).where(
            MessageAttachment.message_id.in_(ids)
        )
    ):
        atts.setdefault(mid, []).append(aid)
    envs: dict[str, MessageEnvelope] = {}
    if device_id:
        for e in db.scalars(
            select(MessageEnvelope).where(
                MessageEnvelope.message_id.in_(ids),
                MessageEnvelope.device_id == device_id,
                MessageEnvelope.delivered_at.is_(None),
            )
        ):
            envs[e.message_id] = e
    out = []
    for m in messages:
        env = envs.get(m.id)
        deleted = m.deleted_at is not None
        out.append(
            {
                "id": m.id,
                "conversation_id": m.conversation_id,
                "sender_user_id": m.sender_user_id,
                "sender_device_id": m.sender_device_id,
                "kind": m.kind,
                "target_message_id": m.target_message_id,
                "client_ts": m.client_ts,
                "server_ts": m.server_ts,
                "seq": m.seq,
                "attachment_ids": [] if deleted else atts.get(m.id, []),
                "deleted": deleted,
                "envelope": None
                if env is None or deleted
                else {"type": env.type, "body": base64.b64encode(env.body).decode()},
            }
        )
    return out

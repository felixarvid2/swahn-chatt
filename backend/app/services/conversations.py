"""Conversations and membership. Non-members never learn whether a conversation exists (404)."""

import uuid

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app import clock
from app.errors import conflict, forbidden, invalid, not_found
from app.models import (
    Attachment,
    Conversation,
    ConversationMember,
    Event,
    Message,
    MessageEnvelope,
    User,
)
from app.services.events import active_device_ids, conversation_member_ids, emit, emit_receipt
from app.services.views import conversations_out


def get_membership(db: Session, conversation_id: str, user_id: str) -> ConversationMember | None:
    return db.get(ConversationMember, (conversation_id, user_id))


def require_member(db: Session, conversation_id: str, user_id: str) -> tuple[Conversation, ConversationMember]:
    member = get_membership(db, conversation_id, user_id)
    conv = db.get(Conversation, conversation_id) if member else None
    if conv is None or member is None:
        raise not_found("conversation not found")
    return conv, member


def require_group_admin(db: Session, conversation_id: str, user_id: str) -> Conversation:
    conv, member = require_member(db, conversation_id, user_id)
    if conv.type != "group":
        raise conflict("not a group conversation")
    if member.role != "admin":
        raise forbidden("group administrators only")
    return conv


def conversation_for(db: Session, conv_id: str, viewer_id: str) -> dict:
    out = conversations_out(db, [conv_id], viewer_id)
    if not out:
        raise not_found("conversation not found")
    return out[0]


def emit_updated(db: Session, conv_id: str) -> None:
    """Tell every device of every member to re-read the conversation (rendered per viewer at sync time)."""
    users = conversation_member_ids(db, conv_id)
    emit(db, active_device_ids(db, users), "conversation_updated", {"conversation_id": conv_id}, conv_id)


def _active_users(db: Session, user_ids: list[str]) -> list[User]:
    unique = list(dict.fromkeys(user_ids))
    users = db.scalars(select(User).where(User.id.in_(unique), User.disabled_at.is_(None))).all()
    if len(users) != len(unique):
        raise not_found("user not found")
    return list(users)


def _check_avatar(db: Session, attachment_id: str | None, user_id: str) -> None:
    if attachment_id is None:
        return
    att = db.get(Attachment, attachment_id)
    if att is None or att.purpose != "avatar" or att.uploader_id != user_id:
        raise invalid("avatar_attachment_id must be an avatar you uploaded")


def create_direct(db: Session, me: str, other_id: str) -> tuple[str, bool]:
    if other_id == me:
        raise invalid("cannot start a conversation with yourself")
    _active_users(db, [other_id])
    key = ":".join(sorted([me, other_id]))
    existing = db.scalar(select(Conversation.id).where(Conversation.direct_key == key))
    if existing:
        if get_membership(db, existing, me) is None or get_membership(db, existing, other_id) is None:
            raise conflict("conversation is no longer available")
        return existing, False
    now = clock.now_ms()
    conv = Conversation(
        id=str(uuid.uuid4()), type="direct", direct_key=key, created_by=me, created_at=now, updated_at=now
    )
    db.add(conv)
    db.flush()
    for uid in (me, other_id):
        db.add(ConversationMember(conversation_id=conv.id, user_id=uid, role="member", joined_at=now, read_seq=0))
    db.flush()
    emit_updated(db, conv.id)
    return conv.id, True


def create_group(db: Session, me: str, name: str, member_ids: list[str], avatar_id: str | None) -> str:
    others = [m for m in dict.fromkeys(member_ids) if m != me]
    _active_users(db, others)
    _check_avatar(db, avatar_id, me)
    now = clock.now_ms()
    conv = Conversation(
        id=str(uuid.uuid4()),
        type="group",
        name=name,
        avatar_attachment_id=avatar_id,
        created_by=me,
        created_at=now,
        updated_at=now,
    )
    db.add(conv)
    db.flush()
    db.add(ConversationMember(conversation_id=conv.id, user_id=me, role="admin", joined_at=now, read_seq=0))
    for uid in others:
        db.add(ConversationMember(conversation_id=conv.id, user_id=uid, role="member", joined_at=now, read_seq=0))
    db.flush()
    emit_updated(db, conv.id)
    return conv.id


def update_conversation(db: Session, conv_id: str, user_id: str, body) -> Conversation:
    """PATCH: `name` and `avatar_attachment_id` are for group admins; `disappearing_seconds` may be set by any member
    of a direct chat and by group admins."""
    conv, member = require_member(db, conv_id, user_id)
    fields = body.model_fields_set
    if conv.type == "group":
        if member.role != "admin":
            raise forbidden("group administrators only")
    elif "disappearing_seconds" not in fields or fields & {"name", "avatar_attachment_id"}:
        raise conflict("not a group conversation")
    if "name" in fields:
        if body.name is None:
            raise invalid("name cannot be empty")
        conv.name = body.name
    if "avatar_attachment_id" in fields:
        _check_avatar(db, body.avatar_attachment_id, user_id)
        conv.avatar_attachment_id = body.avatar_attachment_id
    if "disappearing_seconds" in fields:
        conv.disappearing_seconds = body.disappearing_seconds
    conv.updated_at = clock.now_ms()
    db.flush()
    emit_updated(db, conv.id)
    return conv


def add_members(db: Session, conv: Conversation, user_ids: list[str]) -> None:
    _active_users(db, user_ids)
    now = clock.now_ms()
    for uid in dict.fromkeys(user_ids):
        if get_membership(db, conv.id, uid) is None:
            # New members start with everything read: they get no ciphertext for older messages anyway.
            db.add(
                ConversationMember(
                    conversation_id=conv.id,
                    user_id=uid,
                    role="member",
                    joined_at=now,
                    read_seq=conv.last_message_seq or 0,
                )
            )
    conv.updated_at = now
    db.flush()
    emit_updated(db, conv.id)


def set_role(db: Session, conv: Conversation, user_id: str, role: str) -> None:
    member = get_membership(db, conv.id, user_id)
    if member is None:
        raise not_found("member not found")
    if member.role == "admin" and role == "member":
        admins = db.scalars(
            select(ConversationMember.user_id).where(
                ConversationMember.conversation_id == conv.id, ConversationMember.role == "admin"
            )
        ).all()
        if len(admins) <= 1:
            raise conflict("a group needs at least one administrator")
    member.role = role
    conv.updated_at = clock.now_ms()
    db.flush()
    emit_updated(db, conv.id)


def delete_conversation(db: Session, conv_id: str) -> None:
    db.execute(delete(Event).where(Event.conversation_id == conv_id))
    db.execute(delete(Conversation).where(Conversation.id == conv_id))  # members/messages cascade in the DB
    db.expire_all()


def remove_member(db: Session, conv_id: str, user_id: str) -> None:
    """Remove (or leave): purge what the user's devices still have queued, tell them, then fix up the group."""
    member = get_membership(db, conv_id, user_id)
    if member is None:
        return
    conv = db.get(Conversation, conv_id)
    db.delete(member)
    db.flush()
    device_ids = active_device_ids(db, [user_id])
    if device_ids:
        db.execute(delete(Event).where(Event.device_id.in_(device_ids), Event.conversation_id == conv_id))
        db.execute(
            delete(MessageEnvelope).where(
                MessageEnvelope.device_id.in_(device_ids),
                MessageEnvelope.delivered_at.is_(None),
                MessageEnvelope.message_id.in_(select(Message.id).where(Message.conversation_id == conv_id)),
            )
        )
        emit(db, device_ids, "conversation_removed", {"conversation_id": conv_id}, conv_id)
    remaining = db.scalars(
        select(ConversationMember)
        .where(ConversationMember.conversation_id == conv_id)
        .order_by(ConversationMember.joined_at, ConversationMember.user_id)
    ).all()
    if not remaining:
        delete_conversation(db, conv_id)
        return
    if conv and conv.type == "group" and not any(m.role == "admin" for m in remaining):
        remaining[0].role = "admin"  # longest-standing member
    if conv:
        conv.updated_at = clock.now_ms()
    db.flush()
    emit_updated(db, conv_id)


def remove_member_checked(db: Session, conv: Conversation, actor_id: str, actor_role: str, target_id: str) -> None:
    if conv.type != "group":
        raise conflict("members of a direct conversation cannot be changed")
    if target_id != actor_id:
        if actor_role != "admin":
            raise forbidden("group administrators only")
        if get_membership(db, conv.id, target_id) is None:
            raise not_found("member not found")
    remove_member(db, conv.id, target_id)


def mark_read(
    db: Session, conv: Conversation, member: ConversationMember, device_id: str, up_to_seq: int, notify: bool = True
) -> None:
    """Move the read pointer. With `notify` false only the user's own other devices get the `read` receipt (so their
    unread counts follow); the other members are told nothing."""
    target = min(up_to_seq, conv.last_message_seq or 0)
    if target <= member.read_seq:
        return
    member.read_seq = target
    db.flush()
    users = conversation_member_ids(db, conv.id) if notify else [member.user_id]
    devices = [d for d in active_device_ids(db, users) if d != device_id]
    emit_receipt(
        db,
        devices,
        {"conversation_id": conv.id, "user_id": member.user_id, "type": "read", "up_to_seq": target},
        conv.id,
    )

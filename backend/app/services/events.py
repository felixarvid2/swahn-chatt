"""Event queue fan-out. One row per affected device, written in the caller's transaction.

After the commit (see app/db.py) the Dispatcher hints connected devices over WebSocket and pushes the rest.
"""

import logging
from collections.abc import Iterable

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app import clock
from app.hub import Hub
from app.models import ConversationMember, Device, DeviceKey, Event, User
from app.services.push import PushService

log = logging.getLogger("familychat.events")


class Dispatcher:
    def __init__(self, hub: Hub, push: PushService):
        self.hub = hub
        self.push = push

    def dispatch(self, pending: dict[str, int], kicks: set[str], urgent: set[str] = frozenset()) -> None:
        offline = []
        for device_id, cursor in pending.items():
            if not self.hub.send(device_id, {"type": "sync", "cursor": cursor}):
                offline.append(device_id)
        if offline:
            self.push.notify(offline, urgent=[d for d in offline if d in urgent])
        for device_id in kicks:
            self.hub.kick(device_id)


def emit(
    db: Session,
    device_ids: Iterable[str],
    type_: str,
    payload: dict,
    conversation_id: str | None = None,
    urgent: bool = False,
) -> None:
    ids = list(dict.fromkeys(device_ids))
    if not ids:
        return
    now = clock.now_ms()
    rows = [
        Event(device_id=d, type=type_, conversation_id=conversation_id, payload=payload, created_at=now) for d in ids
    ]
    db.add_all(rows)
    db.flush()
    pending: dict[str, int] = db.info.setdefault("pending_notify", {})
    for row in rows:
        pending[row.device_id] = max(pending.get(row.device_id, 0), row.seq)
    if urgent:
        db.info.setdefault("pending_urgent", set()).update(ids)  # offline ones get a high priority push


def emit_receipt(db: Session, device_ids: Iterable[str], payload: dict, conversation_id: str) -> None:
    """`emit` for a `receipt` event that folds into what the device has not acknowledged yet: of the queued receipts
    with the same conversation, user and type, a read receipt keeps only the highest `up_to_seq`, and delivered
    receipts merge their `message_ids`. The older rows are deleted and one row with a new, higher `seq` is
    inserted, never updated in place: a device that already fetched the old row (its cursor is past it) still gets
    the merged event on its next sync, so cursor semantics are unchanged. Clients see fewer events, not different
    ones."""
    ids = list(dict.fromkeys(device_ids))
    if not ids:
        return
    queued = db.scalars(
        select(Event).where(Event.device_id.in_(ids), Event.type == "receipt", Event.conversation_id == conversation_id)
    ).all()
    mine = [
        e for e in queued if e.payload.get("user_id") == payload["user_id"] and e.payload.get("type") == payload["type"]
    ]
    merged: dict[str, dict] = {}
    for e in sorted(mine, key=lambda e: e.seq):
        merged[e.device_id] = _merge_receipt(merged[e.device_id], e.payload) if e.device_id in merged else e.payload
    if mine:
        db.execute(delete(Event).where(Event.seq.in_([e.seq for e in mine])))
    for device_id in ids:
        emit(
            db,
            [device_id],
            "receipt",
            _merge_receipt(merged[device_id], payload) if device_id in merged else payload,
            conversation_id,
        )


def _merge_receipt(old: dict, new: dict) -> dict:
    if new["type"] == "read":
        return {**new, "up_to_seq": max(old["up_to_seq"], new["up_to_seq"])}
    seen = list(old["message_ids"])
    known = set(seen)
    seen.extend(m for m in new["message_ids"] if m not in known)
    return {**new, "message_ids": seen}


def kick_devices(db: Session, device_ids: Iterable[str]) -> None:
    db.info.setdefault("pending_kick", set()).update(device_ids)


def active_device_ids(db: Session, user_ids: Iterable[str]) -> list[str]:
    ids = list(user_ids)
    if not ids:
        return []
    return list(
        db.scalars(
            select(Device.id)
            .join(User, User.id == Device.user_id)
            .where(Device.user_id.in_(ids), Device.revoked_at.is_(None), User.disabled_at.is_(None))
        )
    )


def keyed_device_rows(db: Session, user_ids: Iterable[str]):
    """Active devices that have uploaded keys: the ones senders must encrypt for."""
    ids = list(user_ids)
    if not ids:
        return []
    return db.execute(
        select(Device.id, Device.user_id)
        .join(DeviceKey, DeviceKey.device_id == Device.id)
        .join(User, User.id == Device.user_id)
        .where(Device.user_id.in_(ids), Device.revoked_at.is_(None), User.disabled_at.is_(None))
    ).all()


def conversation_member_ids(db: Session, conversation_id: str) -> list[str]:
    return list(
        db.scalars(select(ConversationMember.user_id).where(ConversationMember.conversation_id == conversation_id))
    )


def co_member_user_ids(db: Session, user_id: str) -> set[str]:
    """Users sharing at least one conversation with `user_id` (including the user)."""
    mine = select(ConversationMember.conversation_id).where(ConversationMember.user_id == user_id)
    ids = set(db.scalars(select(ConversationMember.user_id).where(ConversationMember.conversation_id.in_(mine))))
    ids.add(user_id)
    return ids


def all_active_user_ids(db: Session) -> list[str]:
    return list(db.scalars(select(User.id).where(User.disabled_at.is_(None))))

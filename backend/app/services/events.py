"""Event queue fan-out. One row per affected device, written in the caller's transaction.

After the commit (see app/db.py) the Dispatcher hints connected devices over WebSocket and pushes the rest.
"""

import logging
from collections.abc import Iterable

from sqlalchemy import select
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

    def dispatch(self, pending: dict[str, int], kicks: set[str]) -> None:
        offline = []
        for device_id, cursor in pending.items():
            if not self.hub.send(device_id, {"type": "sync", "cursor": cursor}):
                offline.append(device_id)
        if offline:
            self.push.notify(offline)
        for device_id in kicks:
            self.hub.kick(device_id)


def emit(
    db: Session,
    device_ids: Iterable[str],
    type_: str,
    payload: dict,
    conversation_id: str | None = None,
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

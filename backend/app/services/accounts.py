"""Accounts, devices and sessions: creation, rotation, revocation."""

import uuid

from sqlalchemy import delete, func, select, update
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.errors import conflict
from app.models import (
    ConversationMember,
    Device,
    DeviceKey,
    Event,
    MessageEnvelope,
    OneTimePqPrekey,
    OneTimePrekey,
    SessionToken,
    User,
)
from app.models import Session as AuthSession
from app.security import create_access_token, hash_token, new_refresh_token
from app.services import conversations as convs
from app.services.events import (
    active_device_ids,
    all_active_user_ids,
    co_member_user_ids,
    emit,
    kick_devices,
)
from app.services.views import device_out_db, user_out

MAX_DEVICE_NUMBER = 127


def _next_device_number(db: Session, user_id: str) -> int:
    used = set(db.scalars(select(Device.number).where(Device.user_id == user_id)))
    for n in range(1, MAX_DEVICE_NUMBER + 1):
        if n not in used:
            return n
    # All numbers taken: recycle the number of the oldest revoked device (parking it on a negative number).
    old = db.scalars(
        select(Device)
        .where(Device.user_id == user_id, Device.revoked_at.is_not(None), Device.number > 0)
        .order_by(Device.revoked_at)
        .limit(1)
    ).first()
    if old is None:
        raise conflict("too many active devices", "device_limit")
    freed = old.number
    low = db.scalar(select(func.min(Device.number)).where(Device.user_id == user_id)) or 0
    old.number = min(low, 0) - 1
    db.flush()
    return freed


def create_device(db: Session, user_id: str, name: str, max_active: int | None = None) -> Device:
    now = clock.now_ms()
    if max_active is not None:
        active = db.scalar(
            select(func.count()).select_from(Device).where(Device.user_id == user_id, Device.revoked_at.is_(None))
        )
        if active >= max_active:
            raise conflict(
                f"this account already has {active} devices (the limit is {max_active}); "
                "log out or remove one of them, then try again",
                "device_limit",
            )
    device = Device(
        id=str(uuid.uuid4()),
        user_id=user_id,
        number=_next_device_number(db, user_id),
        name=name,
        created_at=now,
        last_seen_at=now,
        acked_cursor=0,
    )
    db.add(device)
    db.flush()
    return device


def auth_response(
    db: Session, settings: Settings, user: User, device: Device, session: AuthSession, refresh: str
) -> dict:
    token, exp = create_access_token(settings, user.id, session.id, device.id)
    return {
        "access_token": token,
        "access_expires_at": exp,
        "refresh_token": refresh,
        "refresh_expires_at": session.expires_at,
        "user": user_out(user),
        "device": device_out_db(db, device),
    }


def start_session(db: Session, settings: Settings, user: User, device: Device) -> dict:
    now = clock.now_ms()
    refresh = new_refresh_token()
    session = AuthSession(
        id=str(uuid.uuid4()),
        user_id=user.id,
        device_id=device.id,
        refresh_token_hash=hash_token(refresh),
        created_at=now,
        expires_at=now + settings.refresh_token_days * 86_400_000,
    )
    db.add(session)
    db.flush()
    db.add(SessionToken(token_hash=session.refresh_token_hash, session_id=session.id, issued_at=now))
    db.flush()
    return auth_response(db, settings, user, device, session, refresh)


def rotate_session(db: Session, settings: Settings, session: AuthSession, user: User, device: Device) -> dict:
    refresh = new_refresh_token()
    now = clock.now_ms()
    session.refresh_token_hash = hash_token(refresh)
    session.expires_at = now + settings.refresh_token_days * 86_400_000
    db.add(SessionToken(token_hash=session.refresh_token_hash, session_id=session.id, issued_at=now))
    db.flush()
    return auth_response(db, settings, user, device, session, refresh)


def revoke_device(db: Session, device_id: str, notify: bool = True) -> None:
    """Revoke a device: sessions die, keys and queued data are deleted, co-members learn of it."""
    device = db.get(Device, device_id)
    if device is None or device.revoked_at is not None:
        return
    now = clock.now_ms()
    device.revoked_at = now
    device.push_kind = None
    device.push_endpoint = None
    db.execute(
        update(AuthSession)
        .where(AuthSession.device_id == device_id, AuthSession.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    db.execute(delete(DeviceKey).where(DeviceKey.device_id == device_id))
    db.execute(delete(OneTimePrekey).where(OneTimePrekey.device_id == device_id))
    db.execute(delete(OneTimePqPrekey).where(OneTimePqPrekey.device_id == device_id))
    db.execute(delete(Event).where(Event.device_id == device_id))
    db.execute(
        delete(MessageEnvelope).where(MessageEnvelope.device_id == device_id, MessageEnvelope.delivered_at.is_(None))
    )
    kick_devices(db, [device_id])
    if notify:
        others = [d for d in active_device_ids(db, co_member_user_ids(db, device.user_id)) if d != device_id]
        emit(db, others, "devices_changed", {"user_id": device.user_id})


def revoke_other_sessions(db: Session, user_id: str, keep_device_id: str) -> None:
    for did in db.scalars(
        select(Device.id).where(Device.user_id == user_id, Device.revoked_at.is_(None), Device.id != keep_device_id)
    ).all():
        revoke_device(db, did)


def active_admin_count(db: Session, excluding: str | None = None) -> int:
    """Administrators who can act: not removed, not suspended. Optionally leaving one user out of the count."""
    query = (
        select(func.count())
        .select_from(User)
        .where(User.is_admin.is_(True), User.disabled_at.is_(None), User.suspended_at.is_(None))
    )
    if excluding is not None:
        query = query.where(User.id != excluding)
    return db.scalar(query) or 0


def suspend_user(db: Session, user: User) -> bool:
    """Block the account without touching its data, memberships, devices or sessions; open sockets are closed.
    Returns False when it was already suspended."""
    if user.suspended_at is not None:
        return False
    user.suspended_at = clock.now_ms()
    db.flush()
    kick_devices(db, active_device_ids(db, [user.id]))
    return True


def unsuspend_user(db: Session, user: User) -> bool:
    if user.suspended_at is None:
        return False
    user.suspended_at = None
    db.flush()
    return True


def disable_user(db: Session, user: User) -> None:
    """Admin removal (or self-deletion): account disabled, devices revoked, removed from every conversation.
    The avatar is unlinked so the garbage collector can delete it, and so can the account's other attachments
    that no message references (see attachments.collect_garbage)."""
    if user.disabled_at is not None:
        return
    user.avatar_attachment_id = None
    for did in db.scalars(select(Device.id).where(Device.user_id == user.id, Device.revoked_at.is_(None))).all():
        revoke_device(db, did, notify=False)
    user.disabled_at = clock.now_ms()
    db.flush()
    for conv_id in db.scalars(
        select(ConversationMember.conversation_id).where(ConversationMember.user_id == user.id)
    ).all():
        convs.remove_member(db, conv_id, user.id)
    emit(db, active_device_ids(db, all_active_user_ids(db)), "user_removed", {"user_id": user.id})


def broadcast_user_updated(db: Session, user: User) -> None:
    emit(db, active_device_ids(db, all_active_user_ids(db)), "user_updated", {"user_id": user.id})

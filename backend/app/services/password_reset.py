"""Admin issued password reset codes: single use, valid 24 hours, stored hashed (like invitations)."""

import uuid

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.errors import ApiError
from app.models import Device, PasswordReset, User
from app.security import hash_reset_code, new_invite_code
from app.services import accounts, audit

RESET_VALID_HOURS = 24


def issue(
    db: Session, user: User, created_by: str | None, ip: str | None = None, via: str = "admin"
) -> tuple[str, int]:
    """Create a code for `user`; earlier unused codes of that user stop working. Returns (code, expires_at)."""
    code = new_invite_code()
    now = clock.now_ms()
    db.execute(
        update(PasswordReset)
        .where(PasswordReset.user_id == user.id, PasswordReset.used_at.is_(None), PasswordReset.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    row = PasswordReset(
        id=str(uuid.uuid4()),
        user_id=user.id,
        code_hash=hash_reset_code(code),
        created_by=created_by,
        created_at=now,
        expires_at=now + RESET_VALID_HOURS * 3_600_000,
    )
    db.add(row)
    db.flush()
    audit.record(db, "password_reset_issued", actor=created_by, target_user=user.id, target=row.id, ip=ip, via=via)
    return code, row.expires_at


def _usable(now: int) -> tuple:
    return (PasswordReset.used_at.is_(None), PasswordReset.revoked_at.is_(None), PasswordReset.expires_at > now)


def find_usable(db: Session, code: str) -> tuple[str, str] | None:
    """(reset id, user id) of an unused, unexpired code of an active account."""
    now = clock.now_ms()
    row = db.execute(
        select(PasswordReset.id, PasswordReset.user_id)
        .join(User, User.id == PasswordReset.user_id)
        .where(PasswordReset.code_hash == hash_reset_code(code), User.disabled_at.is_(None), *_usable(now))
    ).first()
    return (row.id, row.user_id) if row else None


def invalid_code() -> ApiError:
    return ApiError(404, "invalid_reset_code", "reset code is invalid, expired or already used")


def redeem(
    db: Session, settings: Settings, reset_id: str, user_id: str, password_hash: str, device_name: str, ip: str
) -> dict:
    """Claim the code atomically (of two concurrent uses exactly one UPDATE matches), set the password, revoke every
    session and device of the user and start a session on a new device. Caller commits."""
    now = clock.now_ms()
    claimed = db.execute(
        update(PasswordReset).where(PasswordReset.id == reset_id, *_usable(clock.now_ms())).values(used_at=now)
    ).rowcount
    if claimed != 1:
        db.rollback()
        raise invalid_code()
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        db.rollback()
        raise invalid_code()
    user.password_hash = password_hash
    old_devices = db.scalars(select(Device.id).where(Device.user_id == user.id, Device.revoked_at.is_(None))).all()
    for device_id in old_devices:
        accounts.revoke_device(db, device_id)  # sessions, keys, queued data, devices_changed to co-members
    # Any other code of this user is moot now.
    db.execute(
        update(PasswordReset)
        .where(PasswordReset.user_id == user.id, PasswordReset.used_at.is_(None), PasswordReset.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    device = accounts.create_device(db, user.id, device_name, settings.max_devices_per_user)
    result = accounts.start_session(db, settings, user, device)
    audit.record(
        db,
        "password_reset_used",
        actor=user.id,
        target_user=user.id,
        target=reset_id,
        ip=ip,
        revoked_devices=len(old_devices),
    )
    return result

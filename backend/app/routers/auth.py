import uuid

from fastapi import APIRouter, Depends, Response
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.deps import Principal, get_db, get_limiter, get_principal, get_rate_ip, get_read_db, get_settings_dep
from app.errors import ApiError, unauthorized
from app.models import Device, Invitation, SessionToken, User
from app.models import Session as AuthSession
from app.ratelimit import RateLimiter
from app.schemas import AuthResponse, LoginRequest, RedeemRequest, RefreshRequest
from app.security import hash_invite_code, hash_password, hash_token, verify_password
from app.services import accounts

router = APIRouter(prefix="/auth", tags=["auth"])


# Failed password guesses per username (whatever the client IP, and whether or not the account exists, so the
# response never reveals which usernames are real): temporary lock after LOGIN_FAILS_PER_HOUR failures within an
# hour, and a longer one after LOGIN_FAILS_PER_DAY within a day. Entries age out, so the lock decays by itself.
LOGIN_FAILS_PER_HOUR = 10
LOGIN_FAILS_PER_DAY = 30
HOUR_S = 3600.0
DAY_S = 86400.0


def _fail_key(username: str) -> str:
    return f"login-fail:{username}"


def _ensure_not_locked(limiter: RateLimiter, username: str) -> None:
    limiter.ensure_below(_fail_key(username) + ":h", LOGIN_FAILS_PER_HOUR, HOUR_S)
    limiter.ensure_below(_fail_key(username) + ":d", LOGIN_FAILS_PER_DAY, DAY_S)


def _record_failure(limiter: RateLimiter, username: str) -> None:
    limiter.record(_fail_key(username) + ":h", HOUR_S)
    limiter.record(_fail_key(username) + ":d", DAY_S)


@router.post("/invite/redeem", status_code=201, response_model=AuthResponse)
def redeem(
    body: RedeemRequest,
    ip: str = Depends(get_rate_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    limiter.check(f"redeem:{ip}", 10)
    now = clock.now_ms()
    code_hash = hash_invite_code(body.code)
    # Cheap rejections first, in a read-only transaction: nothing below may hold a database lock while the
    # (slow, memory hungry) password hash runs.
    inv_id = rdb.scalar(select(Invitation.id).where(Invitation.code_hash == code_hash, *_usable_invite(now)))
    if inv_id is None:
        raise ApiError(404, "invalid_invite", "invitation is invalid, expired or already used")
    if rdb.scalar(select(User.id).where(User.username == body.username)):
        raise ApiError(409, "username_taken", "username already taken")
    rdb.rollback()
    password_hash = hash_password(settings, body.password)

    # Claim the invitation atomically: of two concurrent redeems of one code exactly one UPDATE matches.
    claimed = db.execute(
        update(Invitation).where(Invitation.id == inv_id, *_usable_invite(clock.now_ms())).values(used_at=now)
    ).rowcount
    if claimed != 1:
        db.rollback()
        raise ApiError(404, "invalid_invite", "invitation is invalid, expired or already used")
    is_admin = bool(db.scalar(select(Invitation.is_admin).where(Invitation.id == inv_id)))
    user = User(
        id=str(uuid.uuid4()),
        username=body.username,
        display_name=body.display_name,
        password_hash=password_hash,
        is_admin=is_admin,
        created_at=now,
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()  # also releases the invitation again
        raise ApiError(409, "username_taken", "username already taken") from None
    db.execute(update(Invitation).where(Invitation.id == inv_id).values(used_by=user.id))
    device = accounts.create_device(db, user.id, body.device_name, settings.max_devices_per_user)
    result = accounts.start_session(db, settings, user, device)
    accounts.broadcast_user_updated(db, user)
    db.commit()
    return result


def _usable_invite(now: int) -> tuple:
    return (Invitation.used_at.is_(None), Invitation.revoked_at.is_(None), Invitation.expires_at > now)


@router.post("/login", response_model=AuthResponse)
def login(
    body: LoginRequest,
    ip: str = Depends(get_rate_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    limiter.check(f"login-ip:{ip}", 10)
    limiter.check(f"login-user:{ip}:{body.username}", 5)
    _ensure_not_locked(limiter, body.username)
    found = rdb.execute(
        select(User.id, User.password_hash).where(User.username == body.username, User.disabled_at.is_(None))
    ).first()
    rdb.rollback()  # no transaction while Argon2 runs
    stored = found.password_hash if found else None
    ok = verify_password(settings, stored, body.password)
    if not ok or found is None:
        _record_failure(limiter, body.username)
        raise unauthorized("invalid username or password", "invalid_credentials")
    # Now take the write lock and make sure the account did not change while the hash was being checked.
    user = db.scalar(select(User).where(User.id == found.id, User.disabled_at.is_(None)))
    if user is None or user.password_hash != stored:
        db.rollback()
        raise unauthorized("invalid username or password", "invalid_credentials")
    device = accounts.create_device(db, user.id, body.device_name, settings.max_devices_per_user)
    result = accounts.start_session(db, settings, user, device)
    db.commit()
    return result


@router.post("/refresh", response_model=AuthResponse)
def refresh(
    body: RefreshRequest,
    ip: str = Depends(get_rate_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
):
    limiter.check(f"refresh:{ip}", 30)
    h = hash_token(body.refresh_token)
    session = db.scalar(select(AuthSession).where(AuthSession.refresh_token_hash == h))
    if session is None:
        reused = db.scalar(
            select(AuthSession)
            .join(SessionToken, SessionToken.session_id == AuthSession.id)
            .where(SessionToken.token_hash == h)
        )
        if reused is not None:
            # Any token this session issued before its current one came back: assume theft, kill the session
            # and its device (however many times the thief rotated in between).
            accounts.revoke_device(db, reused.device_id)
            db.commit()
            raise unauthorized("refresh token was already used; session revoked", "token_reused")
        raise unauthorized("invalid refresh token", "invalid_token")
    user = db.get(User, session.user_id)
    device = db.get(Device, session.device_id)
    if (
        session.revoked_at is not None
        or session.expires_at <= clock.now_ms()
        or device is None
        or device.revoked_at is not None
        or user is None
        or user.disabled_at is not None
    ):
        raise unauthorized("invalid refresh token", "invalid_token")
    result = accounts.rotate_session(db, settings, session, user, device)
    db.commit()
    return result


@router.post("/logout", status_code=204)
def logout(p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    accounts.revoke_device(db, p.device_id)
    db.commit()
    return Response(status_code=204)

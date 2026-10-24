import uuid

from fastapi import APIRouter, Depends, Response
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.deps import (
    Principal,
    get_db,
    get_ip,
    get_limiter,
    get_principal,
    get_rate_ip,
    get_read_db,
    get_settings_dep,
)
from app.errors import ApiError, error_responses, unauthorized
from app.models import Device, Invitation, SessionToken, User
from app.models import Session as AuthSession
from app.ratelimit import RateLimiter
from app.schemas import AuthResponse, LoginRequest, PasswordResetRequest, RedeemRequest, RefreshRequest
from app.security import hash_invite_code, hash_password, hash_token, verify_password
from app.services import accounts, audit, password_reset

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


@router.post(
    "/invite/redeem",
    status_code=201,
    response_model=AuthResponse,
    summary="Redeem an invitation and create an account",
    responses=error_responses(404, 409, 413, 422, 429),
)
def redeem(
    body: RedeemRequest,
    addr: str = Depends(get_ip),
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
    audit.record(
        db, "invitation_redeemed", actor=user.id, target_user=user.id, target=inv_id, ip=addr, is_admin=is_admin
    )
    if is_admin:
        audit.record(db, "admin_granted", target_user=user.id, target=inv_id, ip=addr, via="invitation")
    db.commit()
    return result


@router.post(
    "/password/reset",
    response_model=AuthResponse,
    summary="Reset a forgotten password with an admin issued code",
    responses=error_responses(404, 409, 413, 422, 429),
)
def reset_password(
    body: PasswordResetRequest,
    addr: str = Depends(get_ip),
    ip: str = Depends(get_rate_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    limiter.check(f"reset:{ip}", 10)
    found = password_reset.find_usable(rdb, body.code)
    rdb.rollback()  # no transaction while Argon2 runs
    if found is None:
        raise password_reset.invalid_code()
    reset_id, user_id = found
    password_hash = hash_password(settings, body.new_password)
    result = password_reset.redeem(db, settings, reset_id, user_id, password_hash, body.device_name, addr)
    db.commit()
    return result


def _usable_invite(now: int) -> tuple:
    return (Invitation.used_at.is_(None), Invitation.revoked_at.is_(None), Invitation.expires_at > now)


def _audit_login_failure(db: Session, addr: str, user_id: str | None, username: str | None) -> None:
    """Own transaction (the request fails anyway). The name of an account that does not exist is never stored."""
    audit.record(
        db, "login_failure", target_user=user_id, ip=addr, username=username if user_id else audit.UNKNOWN_USER
    )
    db.commit()


@router.post(
    "/login",
    response_model=AuthResponse,
    summary="Log in and register a new device",
    responses=error_responses(401, 409, 413, 422, 429),
)
def login(
    body: LoginRequest,
    addr: str = Depends(get_ip),
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
        _audit_login_failure(db, addr, found.id if found else None, body.username if found else None)
        raise unauthorized("invalid username or password", "invalid_credentials")
    # Now take the write lock and make sure the account did not change while the hash was being checked.
    user = db.scalar(select(User).where(User.id == found.id, User.disabled_at.is_(None)))
    if user is None or user.password_hash != stored:
        db.rollback()
        _audit_login_failure(db, addr, found.id, body.username)
        raise unauthorized("invalid username or password", "invalid_credentials")
    device = accounts.create_device(db, user.id, body.device_name, settings.max_devices_per_user)
    result = accounts.start_session(db, settings, user, device)
    audit.record(db, "login_success", actor=user.id, target_user=user.id, target=device.id, ip=addr)
    db.commit()
    return result


@router.post(
    "/refresh",
    response_model=AuthResponse,
    summary="Rotate the refresh token",
    responses=error_responses(401, 413, 422, 429),
)
def refresh(
    body: RefreshRequest,
    addr: str = Depends(get_ip),
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
            audit.record(
                db,
                "refresh_token_reuse",
                target_user=reused.user_id,
                target=reused.device_id,
                ip=addr,
                session=reused.id,
            )
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


@router.post(
    "/logout", status_code=204, summary="Log out: revoke this session and device", responses=error_responses(401)
)
def logout(p: Principal = Depends(get_principal), addr: str = Depends(get_ip), db: Session = Depends(get_db)):
    accounts.revoke_device(db, p.device_id)
    audit.record(db, "device_revoked", actor=p.user_id, target_user=p.user_id, target=p.device_id, ip=addr, by="logout")
    db.commit()
    return Response(status_code=204)

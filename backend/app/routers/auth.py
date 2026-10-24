import uuid

from fastapi import APIRouter, Depends, Response
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.deps import Principal, get_db, get_ip, get_limiter, get_principal, get_settings_dep
from app.errors import ApiError, unauthorized
from app.models import Device, Invitation, User
from app.models import Session as AuthSession
from app.ratelimit import RateLimiter
from app.schemas import AuthResponse, LoginRequest, RedeemRequest, RefreshRequest
from app.security import hash_invite_code, hash_password, hash_token, verify_password
from app.services import accounts

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/invite/redeem", status_code=201, response_model=AuthResponse)
def redeem(
    body: RedeemRequest,
    ip: str = Depends(get_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
):
    limiter.check(f"redeem:{ip}", 10)
    now = clock.now_ms()
    inv = db.scalar(select(Invitation).where(Invitation.code_hash == hash_invite_code(body.code)))
    if inv is None or inv.used_at is not None or inv.revoked_at is not None or inv.expires_at <= now:
        raise ApiError(404, "invalid_invite", "invitation is invalid, expired or already used")
    if db.scalar(select(User.id).where(User.username == body.username)):
        raise ApiError(409, "username_taken", "username already taken")
    user = User(
        id=str(uuid.uuid4()),
        username=body.username,
        display_name=body.display_name,
        password_hash=hash_password(settings, body.password),
        is_admin=bool(inv.is_admin),
        created_at=now,
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, "username_taken", "username already taken") from None
    inv.used_at = now
    inv.used_by = user.id
    device = accounts.create_device(db, user.id, body.device_name)
    result = accounts.start_session(db, settings, user, device)
    accounts.broadcast_user_updated(db, user)
    db.commit()
    return result


@router.post("/login", response_model=AuthResponse)
def login(
    body: LoginRequest,
    ip: str = Depends(get_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
):
    limiter.check(f"login-ip:{ip}", 10)
    limiter.check(f"login-user:{ip}:{body.username}", 5)
    user = db.scalar(select(User).where(User.username == body.username, User.disabled_at.is_(None)))
    ok = verify_password(settings, user.password_hash if user else None, body.password)
    if not ok or user is None:
        raise unauthorized("invalid username or password", "invalid_credentials")
    device = accounts.create_device(db, user.id, body.device_name)
    result = accounts.start_session(db, settings, user, device)
    db.commit()
    return result


@router.post("/refresh", response_model=AuthResponse)
def refresh(
    body: RefreshRequest,
    ip: str = Depends(get_ip),
    limiter: RateLimiter = Depends(get_limiter),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
):
    limiter.check(f"refresh:{ip}", 30)
    h = hash_token(body.refresh_token)
    session = db.scalar(select(AuthSession).where(AuthSession.refresh_token_hash == h))
    if session is None:
        reused = db.scalar(select(AuthSession).where(AuthSession.previous_token_hash == h))
        if reused is not None:
            # A rotated token came back: assume theft, kill the session and its device.
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

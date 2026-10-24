"""FastAPI dependencies: settings, DB session, authenticated principal, client IP."""

from collections.abc import Iterator
from dataclasses import dataclass

from fastapi import Depends, Request
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.errors import ApiError, unauthorized
from app.models import Device, User
from app.models import Session as AuthSession
from app.ratelimit import RateLimiter, client_ip
from app.security import decode_access_token

LAST_SEEN_GRANULARITY_MS = 60_000


@dataclass(frozen=True)
class Principal:
    user_id: str
    device_id: str
    session_id: str
    username: str
    is_admin: bool


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request) -> Iterator[Session]:
    db = request.app.state.session_factory()
    try:
        yield db
    finally:
        db.close()


def get_limiter(request: Request) -> RateLimiter:
    return request.app.state.limiter


def get_ip(request: Request) -> str:
    return client_ip(request, request.app.state.settings.trusted_networks)


def authenticate(app, token: str) -> Principal:
    """Verify the JWT, then re-check session, device and user in the DB (so revocation is immediate).

    Uses its own short transaction so no database lock is held while the endpoint runs.
    """
    claims = decode_access_token(app.state.settings, token)
    now = clock.now_ms()
    with app.state.session_factory() as db:
        row = db.execute(
            select(
                User.id,
                User.username,
                User.is_admin,
                User.disabled_at,
                AuthSession.revoked_at,
                AuthSession.expires_at,
                Device.revoked_at.label("device_revoked"),
                Device.last_seen_at,
            )
            .join(AuthSession, AuthSession.user_id == User.id)
            .join(Device, Device.id == AuthSession.device_id)
            .where(
                User.id == claims.user_id,
                AuthSession.id == claims.session_id,
                AuthSession.device_id == claims.device_id,
            )
        ).first()
        if (
            row is None
            or row.disabled_at is not None
            or row.revoked_at is not None
            or row.device_revoked is not None
            or row.expires_at <= now
        ):
            raise unauthorized("session is no longer valid")
        if now - row.last_seen_at > LAST_SEEN_GRANULARITY_MS:
            db.execute(update(Device).where(Device.id == claims.device_id).values(last_seen_at=now))
            db.commit()
        return Principal(row.id, claims.device_id, claims.session_id, row.username, bool(row.is_admin))


def bearer_token(request: Request) -> str:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise unauthorized()
    return token.strip()


def get_principal(request: Request) -> Principal:
    return authenticate(request.app, bearer_token(request))


def require_admin(p: Principal = Depends(get_principal)) -> Principal:
    if not p.is_admin:
        raise ApiError(403, "forbidden", "administrator only")
    return p

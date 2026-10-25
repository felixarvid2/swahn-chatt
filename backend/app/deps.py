"""FastAPI dependencies: settings, DB session, authenticated principal, client IP."""

import logging
from collections.abc import Iterator
from dataclasses import dataclass

from fastapi import Depends, Request
from sqlalchemy import select, update
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.errors import ApiError, account_suspended, unauthorized
from app.models import Device, User
from app.models import Session as AuthSession
from app.ratelimit import RateLimiter, client_ip, rate_key
from app.security import AccessClaims, decode_access_token
from app.services.alerts import AdminAlerts

log = logging.getLogger("familychat.deps")

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


def get_read_db(request: Request) -> Iterator[Session]:
    """For routes that only read: no write lock is taken (see db.make_read_session_factory)."""
    db = request.app.state.read_session_factory()
    try:
        yield db
    finally:
        db.close()


def get_limiter(request: Request) -> RateLimiter:
    return request.app.state.limiter


def get_alerts(request: Request) -> AdminAlerts:
    return request.app.state.alerts


def get_ip(request: Request) -> str:
    return client_ip(request, request.app.state.settings.trusted_networks)


def get_rate_ip(request: Request) -> str:
    """Client identity for rate limiting (IPv6 grouped by /64); get_ip keeps the exact address for logs."""
    return rate_key(get_ip(request))


def authenticate(app, token: str) -> Principal:
    """Verify the JWT, then re-check session, device and user in the DB (so revocation is immediate)."""
    claims = decode_access_token(app.state.settings, token)
    principal, stale_seen = check_session(app, claims)
    if stale_seen:
        touch_last_seen(app, claims.device_id)
    return principal


def check_session(app, claims: AccessClaims) -> tuple[Principal, bool]:
    """The database half of authentication (no token expiry check): the session, its device and its user must
    all still be valid. Read-only, so no database write lock is held while the endpoint runs.
    Returns the principal and whether the device's last_seen_at is due for a refresh."""
    now = clock.now_ms()
    with app.state.read_session_factory() as db:
        row = db.execute(
            select(
                User.id,
                User.username,
                User.is_admin,
                User.disabled_at,
                User.suspended_at,
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
    if row.suspended_at is not None:
        raise account_suspended()
    principal = Principal(row.id, claims.device_id, claims.session_id, row.username, bool(row.is_admin))
    return principal, now - row.last_seen_at > LAST_SEEN_GRANULARITY_MS


def touch_last_seen(app, device_id: str) -> None:
    """Best effort, in its own short write transaction: a busy database must never fail a request."""
    try:
        with app.state.session_factory() as db:
            db.execute(update(Device).where(Device.id == device_id).values(last_seen_at=clock.now_ms()))
            db.commit()
    except OperationalError:
        log.debug("last_seen_at update skipped (database busy)")


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


def require_admin_write(p: Principal = Depends(require_admin), db: Session = Depends(get_db)) -> Principal:
    """`require_admin` for routes that write. The principal was read before the handler ran, so a concurrent request
    may have suspended, removed or demoted this admin since. Writers are serialised by the first statement of the
    request's write transaction (BEGIN IMMEDIATE / advisory lock, see db.py), so this fresh read, made in that same
    transaction (get_db is shared with the handler), sees every earlier writer's commit and nothing changes before
    the handler commits. Two admins suspending each other can therefore never both succeed."""
    row = db.execute(select(User.is_admin, User.disabled_at, User.suspended_at).where(User.id == p.user_id)).first()
    if row is None or row.disabled_at is not None:
        db.rollback()
        raise unauthorized("session is no longer valid")
    if row.suspended_at is not None:
        db.rollback()
        raise account_suspended()
    if not row.is_admin:
        db.rollback()
        raise ApiError(403, "forbidden", "administrator only")
    return p

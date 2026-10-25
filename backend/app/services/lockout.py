"""Per-username login lockout. The counters live in the rate limiter (memory, fast), and every failure is also
written to `login_failures` so a restart does not hand an attacker a fresh set of guesses: at startup the table
is replayed into the limiter. Only failures are written (a few rows, never one per request). The table holds an
HMAC of the username, not the name: unknown accounts must not be stored (same rule as the audit log)."""

import hashlib
import hmac
import logging

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.models import LoginFailure
from app.ratelimit import RateLimiter

log = logging.getLogger("swahnchat.lockout")

# Failed password guesses per username (whatever the client IP, and whether or not the account exists, so the
# response never reveals which usernames are real): temporary lock after LOGIN_FAILS_PER_HOUR failures within an
# hour, and a longer one after LOGIN_FAILS_PER_DAY within a day. Entries age out, so the lock decays by itself.
LOGIN_FAILS_PER_HOUR = 10
LOGIN_FAILS_PER_DAY = 30
HOUR_S = 3600.0
DAY_S = 86400.0


def key_hash(settings: Settings, username: str) -> str:
    return hmac.new(settings.secret_key.encode(), b"login-failure:" + username.encode(), hashlib.sha256).hexdigest()


def _keys(digest: str) -> tuple[str, str]:
    return f"login-fail:{digest}:h", f"login-fail:{digest}:d"


def ensure_not_locked(limiter: RateLimiter, settings: Settings, username: str) -> None:
    hourly, daily = _keys(key_hash(settings, username))
    limiter.ensure_below(hourly, LOGIN_FAILS_PER_HOUR, HOUR_S)
    limiter.ensure_below(daily, LOGIN_FAILS_PER_DAY, DAY_S)


def record_failure(db: Session, limiter: RateLimiter, settings: Settings, username: str) -> bool:
    """Count the failure and persist it in the caller's transaction (the login route commits it with the audit row).
    Returns True when this failure is the one that locked the username (for the operator alert)."""
    digest = key_hash(settings, username)
    hourly, daily = _keys(digest)
    limiter.record(hourly, HOUR_S)
    limiter.record(daily, DAY_S)
    if limiter.enabled:
        db.add(LoginFailure(key_hash=digest, at=clock.now_ms()))
        db.flush()
    return limiter.count(hourly, HOUR_S) == LOGIN_FAILS_PER_HOUR or limiter.count(daily, DAY_S) == LOGIN_FAILS_PER_DAY


def restore(db: Session, limiter: RateLimiter) -> int:
    """Replay the last day of persisted failures into a fresh limiter (app start). Returns the number of rows."""
    if not limiter.enabled:
        return 0
    now = clock.now_ms()
    by_key: dict[str, list[float]] = {}
    for digest, at in db.execute(
        select(LoginFailure.key_hash, LoginFailure.at).where(LoginFailure.at > now - 86_400_000)
    ):
        by_key.setdefault(digest, []).append(max(now - at, 0) / 1000.0)
    for digest, ages in by_key.items():
        hourly, daily = _keys(digest)
        limiter.seed(hourly, HOUR_S, ages)
        limiter.seed(daily, DAY_S, ages)
    return sum(len(v) for v in by_key.values())


def prune(db: Session, now: int) -> int:
    return db.execute(delete(LoginFailure).where(LoginFailure.at < now - 86_400_000)).rowcount

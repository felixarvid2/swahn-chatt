"""Password hashing (Argon2id), JWT access tokens, opaque token hashing."""

import hashlib
import hmac
import secrets
import threading
from dataclasses import dataclass
from functools import lru_cache

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

from app import clock
from app.config import Settings
from app.errors import unauthorized

JWT_ALG = "HS256"
# Each Argon2 run needs ~64 MiB; the request thread pool is much larger than a Raspberry Pi can afford, so at
# most this many hashes/verifications run at once (the rest wait, without holding any database transaction).
ARGON2_CONCURRENCY = 2
_argon2_slots = threading.BoundedSemaphore(ARGON2_CONCURRENCY)
INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I


@lru_cache
def _hasher(time_cost: int, memory_kb: int, parallelism: int) -> PasswordHasher:
    return PasswordHasher(time_cost=time_cost, memory_cost=memory_kb, parallelism=parallelism)


def hasher(settings: Settings) -> PasswordHasher:
    return _hasher(settings.argon2_time_cost, settings.argon2_memory_kb, settings.argon2_parallelism)


def hash_password(settings: Settings, password: str) -> str:
    with _argon2_slots:
        return hasher(settings).hash(password)


def verify_password(settings: Settings, stored_hash: str | None, password: str) -> bool:
    """Constant-ish time: an unknown user is verified against a dummy hash."""
    h = hasher(settings)
    with _argon2_slots:
        if stored_hash is None:
            try:
                h.verify(_dummy_hash(settings), password)  # (first use hashes it here, inside the slot)
            except (VerificationError, InvalidHashError):
                pass
            return False
        try:
            return h.verify(stored_hash, password)
        except (VerificationError, InvalidHashError):
            return False


@lru_cache
def _dummy_hash_cached(time_cost: int, memory_kb: int, parallelism: int) -> str:
    return _hasher(time_cost, memory_kb, parallelism).hash(secrets.token_urlsafe(16))


def _dummy_hash(settings: Settings) -> str:
    return _dummy_hash_cached(settings.argon2_time_cost, settings.argon2_memory_kb, settings.argon2_parallelism)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_refresh_token() -> str:
    return secrets.token_urlsafe(32)  # 256 bits


def new_invite_code() -> str:
    raw = "".join(secrets.choice(INVITE_ALPHABET) for _ in range(20))  # ~100 bits
    return "-".join(raw[i : i + 5] for i in range(0, 20, 5))


def normalize_invite_code(code: str) -> str:
    return "".join(c for c in code.upper() if c.isalnum())


def hash_invite_code(code: str) -> str:
    return hash_token(normalize_invite_code(code))


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode(), b.encode())


@dataclass(frozen=True)
class AccessClaims:
    user_id: str
    session_id: str
    device_id: str


def create_access_token(settings: Settings, user_id: str, session_id: str, device_id: str) -> tuple[str, int]:
    now_ms = clock.now_ms()
    exp_ms = now_ms + settings.access_token_minutes * 60_000
    token = jwt.encode(
        {"sub": user_id, "sid": session_id, "did": device_id, "iat": now_ms // 1000, "exp": exp_ms // 1000},
        settings.secret_key,
        algorithm=JWT_ALG,
    )
    return token, exp_ms


def decode_access_token(settings: Settings, token: str) -> AccessClaims:
    try:
        data = jwt.decode(
            token, settings.secret_key, algorithms=[JWT_ALG], options={"require": ["exp", "sub", "sid", "did"]}
        )
    except jwt.ExpiredSignatureError:
        raise unauthorized("access token expired", "token_expired") from None
    except jwt.PyJWTError:
        raise unauthorized("invalid access token") from None
    return AccessClaims(data["sub"], data["sid"], data["did"])

"""In-process sliding-window rate limiter (resets on restart; see SECURITY.md)."""

import ipaddress
import math
import threading
import time
from collections import defaultdict, deque

from starlette.requests import HTTPConnection

from app.errors import ApiError


class RateLimiter:
    def __init__(self, enabled: bool = True, clock=time.monotonic):
        self.enabled = enabled
        self._clock = clock
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._windows: dict[str, float] = {}
        self._lock = threading.Lock()

    def _trim(self, key: str, now: float, window: float) -> deque[float]:
        q = self._hits[key]
        self._windows[key] = max(window, self._windows.get(key, 0.0))
        while q and q[0] <= now - window:
            q.popleft()
        return q

    @staticmethod
    def _too_many(q: deque[float], now: float, window: float) -> ApiError:
        retry = max(1, math.ceil(q[0] + window - now))
        return ApiError(429, "rate_limited", "too many requests, slow down", headers={"Retry-After": str(retry)})

    def check(self, key: str, limit: int, window: float = 60.0) -> None:
        """Record one attempt; raise 429 when more than `limit` happened within `window` seconds."""
        if not self.enabled:
            return
        now = self._clock()
        with self._lock:
            q = self._trim(key, now, window)
            if len(q) >= limit:
                raise self._too_many(q, now, window)
            q.append(now)

    def allow(self, key: str, limit: int, window: float = 60.0) -> bool:
        """Like check(), but answers instead of raising (the attempt is recorded only when allowed)."""
        try:
            self.check(key, limit, window)
        except ApiError:
            return False
        return True

    def ensure_below(self, key: str, limit: int, window: float) -> None:
        """Raise 429 when `key` already has `limit` or more recorded events in `window`; records nothing."""
        if not self.enabled:
            return
        now = self._clock()
        with self._lock:
            q = self._trim(key, now, window)
            if len(q) >= limit:
                raise self._too_many(q, now, window)

    def record(self, key: str, window: float) -> None:
        """Record one event (for failure counters that are checked with ensure_below)."""
        if not self.enabled:
            return
        now = self._clock()
        with self._lock:
            self._trim(key, now, window).append(now)

    def count(self, key: str, window: float) -> int:
        """Events recorded for `key` within `window` seconds."""
        if not self.enabled:
            return 0
        now = self._clock()
        with self._lock:
            return len(self._trim(key, now, window))

    def seed(self, key: str, window: float, ages: list[float]) -> None:
        """Re-create events that happened `ages` seconds ago (used at startup to restore persisted failure counters)."""
        if not self.enabled:
            return
        now = self._clock()
        with self._lock:
            q = self._trim(key, now, window)
            q.extend(now - a for a in sorted(ages, reverse=True) if a < window)

    def clear(self, key: str) -> None:
        with self._lock:
            self._hits.pop(key, None)
            self._windows.pop(key, None)

    def cleanup(self) -> None:
        now = self._clock()
        with self._lock:
            for key in [k for k, q in self._hits.items() if not q or q[-1] <= now - self._windows.get(k, 3600.0)]:
                del self._hits[key]
                self._windows.pop(key, None)


def rate_key(ip: str) -> str:
    """Rate limit identity of a client address: IPv6 clients get a whole /64 (a single subscriber normally owns
    at least that, so rotating addresses inside it must not reset the limits); IPv4-mapped IPv6 is plain IPv4."""
    try:
        addr = ipaddress.ip_address(ip.split("%", 1)[0])
    except ValueError:
        return ip
    if isinstance(addr, ipaddress.IPv6Address):
        if addr.ipv4_mapped is not None:
            return str(addr.ipv4_mapped)
        return str(ipaddress.ip_network(f"{addr}/64", strict=False))
    return str(addr)


def client_ip(conn: HTTPConnection, trusted: list) -> str:
    """Peer address, or the right-most untrusted X-Forwarded-For hop when the peer is a trusted proxy."""
    peer = conn.client.host if conn.client else "unknown"
    if not trusted:
        return peer
    try:
        peer_ip = ipaddress.ip_address(peer)
    except ValueError:
        return peer
    if not any(peer_ip in net for net in trusted):
        return peer
    hops = [h.strip() for h in conn.headers.get("x-forwarded-for", "").split(",") if h.strip()]
    for hop in reversed(hops):
        try:
            ip = ipaddress.ip_address(hop)
        except ValueError:
            return peer
        if not any(ip in net for net in trusted):
            return str(ip)
    return hops[0] if hops else peer

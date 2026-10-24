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
        self._lock = threading.Lock()

    def check(self, key: str, limit: int, window: float = 60.0) -> None:
        """Record one attempt; raise 429 when more than `limit` happened within `window` seconds."""
        if not self.enabled:
            return
        now = self._clock()
        with self._lock:
            q = self._hits[key]
            while q and q[0] <= now - window:
                q.popleft()
            if len(q) >= limit:
                retry = max(1, math.ceil(q[0] + window - now))
                raise ApiError(429, "rate_limited", "too many requests, slow down", headers={"Retry-After": str(retry)})
            q.append(now)

    def cleanup(self, window: float = 3600.0) -> None:
        now = self._clock()
        with self._lock:
            for key in [k for k, q in self._hits.items() if not q or q[-1] <= now - window]:
                del self._hits[key]


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

"""UnifiedPush sender. Content-free ({"t":"sync"}), on a worker thread, 5 s timeout, https only."""

import ipaddress
import logging
import queue
import socket
import threading
from collections.abc import Callable
from urllib.parse import urlsplit

import httpx
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.models import Device

log = logging.getLogger("familychat.push")

PUSH_PAYLOAD = {"t": "sync"}


def parse_hosts(value: str) -> frozenset[str]:
    return frozenset(h.strip().lower() for h in value.split(",") if h.strip())


def is_ip_literal(host: str) -> bool:
    """True for anything a resolver would read as a numeric address, incl. 2130706433 and 0x7f.1 (loopback)."""
    host = host.strip("[]")
    try:
        ipaddress.ip_address(host.split("%", 1)[0])
        return True
    except ValueError:
        pass
    try:
        socket.inet_aton(host)  # legacy shorthand forms (decimal, octal, hex, fewer than four parts)
        return True
    except OSError:
        return False


def endpoint_allowed(
    endpoint: str,
    allow_http: bool,
    allowed_hosts: frozenset[str] = frozenset(),
    require_allowlist: bool = False,
) -> bool:
    """https only (http when allowed) and an SSRF guard on the host: it must be listed in `allowed_hosts`;
    with `require_allowlist` (production) an empty list allows nothing; IP literals are only allowed when listed."""
    parts = urlsplit(endpoint)
    host = (parts.hostname or "").lower()
    if not host:
        return False
    if allowed_hosts:
        if host not in allowed_hosts:
            return False
    elif require_allowlist or is_ip_literal(host):
        return False
    return parts.scheme == "https" or (allow_http and parts.scheme == "http")


class PushService:
    def __init__(
        self,
        session_factory: Callable[[], Session],
        read_session_factory: Callable[[], Session] | None = None,
        allow_http: bool = False,
        allowed_hosts: frozenset[str] = frozenset(),
        require_allowlist: bool = False,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 5.0,
    ):
        self._session_factory = session_factory
        self._read_session_factory = read_session_factory or session_factory
        self._allow_http = allow_http
        self._allowed_hosts = allowed_hosts
        self._require_allowlist = require_allowlist
        self._client = httpx.Client(transport=transport, timeout=timeout, follow_redirects=False)
        self._queue: queue.Queue[str | None] = queue.Queue()
        self._pending: set[str] = set()
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self.sent = 0

    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, name="push", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        if self._thread is not None:
            self._queue.put(None)
            self._thread.join(timeout=10)
            self._thread = None
        self._client.close()

    def notify(self, device_ids) -> None:
        """Queue a push for the devices (non-blocking). Duplicates already waiting are coalesced."""
        for did in device_ids:
            with self._lock:
                if did in self._pending:
                    continue
                self._pending.add(did)
            self._queue.put(did)

    def pending_count(self) -> int:
        """Pushes waiting in the queue (for /metrics)."""
        return self._queue.qsize()

    def flush(self, timeout: float = 10.0) -> None:
        """Wait until the queue is drained (tests)."""
        done = threading.Event()
        threading.Thread(target=lambda: (self._queue.join(), done.set()), daemon=True).start()
        done.wait(timeout)

    def _run(self) -> None:
        while True:
            did = self._queue.get()
            try:
                if did is None:
                    return
                with self._lock:
                    self._pending.discard(did)
                self._send(did)
            except Exception:
                log.exception("push worker error")
            finally:
                self._queue.task_done()

    def _send(self, device_id: str) -> None:
        with self._read_session_factory() as db:
            dev = db.execute(
                select(Device.push_kind, Device.push_endpoint).where(
                    Device.id == device_id, Device.revoked_at.is_(None)
                )
            ).first()
        if not dev or dev.push_kind != "unifiedpush" or not dev.push_endpoint:
            return
        if not endpoint_allowed(dev.push_endpoint, self._allow_http, self._allowed_hosts, self._require_allowlist):
            log.warning("push endpoint rejected (scheme or host not allowed)")
            return
        host = urlsplit(dev.push_endpoint).hostname
        try:
            resp = self._client.post(dev.push_endpoint, json=PUSH_PAYLOAD)
        except httpx.HTTPError as exc:
            log.warning("push failed host=%s error=%s", host, type(exc).__name__)
            return
        if resp.status_code in (404, 410):  # the distributor dropped the subscription
            with self._session_factory() as db:
                db.execute(
                    update(Device)
                    .where(Device.id == device_id, Device.push_endpoint == dev.push_endpoint)
                    .values(push_kind=None, push_endpoint=None)
                )
                db.commit()
            log.info("push endpoint gone (status %s), cleared", resp.status_code)
        elif resp.status_code >= 400:
            log.warning("push rejected host=%s status=%s", host, resp.status_code)
        else:
            self.sent += 1

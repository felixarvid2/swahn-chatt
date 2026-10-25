"""UnifiedPush sender. Content-free ({"t":"sync"}), on a worker thread, 5 s timeout, https only. Transient failures
(network error, 429, 5xx) are retried later with backoff; the outcome of each device's last push is recorded."""

import ipaddress
import logging
import queue
import socket
import threading
import time
from collections.abc import Callable
from urllib.parse import urlsplit

import httpx
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app import clock
from app.models import Device

log = logging.getLogger("familychat.push")

PUSH_PAYLOAD = {"t": "sync"}
# Call signalling (kind "call") pushes carry the same content-free payload but ask for immediate delivery: ntfy reads
# Priority (5 = max, wakes the phone through the distributor's high priority path), RFC 8030 push services read Urgency.
URGENT_HEADERS = {"Priority": "5", "Urgency": "high"}
# Call signalling (kind "call") pushes carry the same content-free payload but ask for immediate delivery: ntfy reads
# Priority (5 = max, wakes the phone through the distributor's high priority path), RFC 8030 push services read Urgency.
URGENT_HEADERS = {"Priority": "5", "Urgency": "high"}
# Waits before the 2nd, 3rd ... attempt (so 3 attempts in all). The wait happens in a schedule, not in the worker.
RETRY_DELAYS = (5.0, 30.0, 120.0)
NO_RESPONSE = 0  # last_push_status when the request got no HTTP answer (network error, timeout)


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
        retry_delays: tuple[float, ...] = RETRY_DELAYS,
    ):
        self._session_factory = session_factory
        self._read_session_factory = read_session_factory or session_factory
        self._allow_http = allow_http
        self._allowed_hosts = allowed_hosts
        self._require_allowlist = require_allowlist
        self._retry_delays = retry_delays
        self._client = httpx.Client(transport=transport, timeout=timeout, follow_redirects=False)
        self._queue: queue.Queue[str | None] = queue.Queue()
        self._pending: set[str] = set()
        self._urgent: set[str] = set()  # devices whose next push is high priority
        # Pushes waiting for their next attempt: (due monotonic time, device id, attempts made, urgent). Each entry
        # keeps its queue task open (task_done comes with the final outcome), so flush() also waits for retries.
        self._retries: list[tuple[float, str, int, bool]] = []
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

    def notify(self, device_ids, urgent=()) -> None:
        """Queue a push for the devices (non-blocking). Duplicates already waiting are coalesced; a device in
        `urgent` gets a high priority push (a coalesced push is upgraded)."""
        urgent = set(urgent)
        for did in device_ids:
            with self._lock:
                if did in urgent:
                    self._urgent.add(did)
                if did in self._pending:
                    continue
                self._pending.add(did)
            self._queue.put(did)

    def pending_count(self) -> int:
        """Pushes waiting in the queue or for a retry (for /metrics)."""
        return self._queue.qsize() + len(self._retries)

    def flush(self, timeout: float = 10.0) -> None:
        """Wait until the queue is drained, retries included (tests)."""
        done = threading.Event()
        threading.Thread(target=lambda: (self._queue.join(), done.set()), daemon=True).start()
        done.wait(timeout)

    def _next_due(self) -> float | None:
        with self._lock:
            return min((r[0] for r in self._retries), default=None)

    def _take_due(self) -> list[tuple[str, int, bool]]:
        now = time.monotonic()
        with self._lock:
            due = [r for r in self._retries if r[0] <= now]
            self._retries = [r for r in self._retries if r[0] > now]
        return [(did, attempts, urgent) for _, did, attempts, urgent in sorted(due)]

    def _drop_retries(self, device_id: str) -> None:
        """A fresh push for the device makes its scheduled retries redundant (the hint is the same)."""
        with self._lock:
            dropped = [r for r in self._retries if r[1] == device_id]
            self._retries = [r for r in self._retries if r[1] != device_id]
        for _ in dropped:
            self._queue.task_done()

    def _run(self) -> None:
        while True:
            due = self._next_due()
            try:
                did = self._queue.get(timeout=None if due is None else max(due - time.monotonic(), 0.0))
            except queue.Empty:
                for retry_id, attempts, urgent in self._take_due():
                    self._process(retry_id, attempts, urgent)
                continue
            if did is None:
                self._queue.task_done()
                return
            with self._lock:
                self._pending.discard(did)
                urgent = did in self._urgent
                self._urgent.discard(did)
            self._drop_retries(did)
            self._process(did, 0, urgent)

    def _process(self, device_id: str, attempts: int, urgent: bool = False) -> None:
        """One delivery attempt (`attempts` = how many retries came before). The attempt's queue task stays open
        while a retry is scheduled and is closed with the final outcome."""
        rescheduled = False
        try:
            if self._send(device_id, urgent) == "retry":
                if attempts < len(self._retry_delays):
                    with self._lock:
                        self._retries.append(
                            (time.monotonic() + self._retry_delays[attempts], device_id, attempts + 1, urgent)
                        )
                    rescheduled = True
                else:
                    self._record(device_id, None, failed=True)  # retries exhausted
        except Exception:
            log.exception("push worker error")
        finally:
            if not rescheduled:
                self._queue.task_done()

    def _record(self, device_id: str, status: int | None, failed: bool = False, ok: bool = False) -> None:
        """Store the outcome of an attempt. `status` None keeps the last status (only the failure count changes)."""
        values: dict = {}
        if status is not None:
            values.update(last_push_at=clock.now_ms(), last_push_status=status)
        if ok:
            values["push_failures"] = 0
        elif failed:
            values["push_failures"] = Device.push_failures + 1
        if not values:
            return
        try:
            with self._session_factory() as db:
                db.execute(update(Device).where(Device.id == device_id).values(**values))
                db.commit()
        except Exception:  # health bookkeeping must never break delivery
            log.warning("could not record push health", exc_info=True)

    def _send(self, device_id: str, urgent: bool = False) -> str:
        """Returns "done", or "retry" for a transient failure (network error, 429, 5xx)."""
        with self._read_session_factory() as db:
            dev = db.execute(
                select(Device.push_kind, Device.push_endpoint).where(
                    Device.id == device_id, Device.revoked_at.is_(None)
                )
            ).first()
        if not dev or dev.push_kind != "unifiedpush" or not dev.push_endpoint:
            return "done"
        if not endpoint_allowed(dev.push_endpoint, self._allow_http, self._allowed_hosts, self._require_allowlist):
            log.warning("push endpoint rejected (scheme or host not allowed)")
            return "done"
        host = urlsplit(dev.push_endpoint).hostname
        try:
            resp = self._client.post(dev.push_endpoint, json=PUSH_PAYLOAD, headers=URGENT_HEADERS if urgent else None)
        except httpx.HTTPError as exc:
            log.warning("push failed host=%s error=%s", host, type(exc).__name__)
            self._record(device_id, NO_RESPONSE)
            return "retry"
        code = resp.status_code
        if code in (404, 410):  # the distributor dropped the subscription
            with self._session_factory() as db:
                db.execute(
                    update(Device)
                    .where(Device.id == device_id, Device.push_endpoint == dev.push_endpoint)
                    .values(
                        push_kind=None,
                        push_endpoint=None,
                        last_push_at=clock.now_ms(),
                        last_push_status=code,
                        push_failures=0,
                    )
                )
                db.commit()
            log.info("push endpoint gone (status %s), cleared", code)
        elif code == 429 or code >= 500:
            log.warning("push failed host=%s status=%s", host, code)
            self._record(device_id, code)
            return "retry"
        elif code >= 400:
            log.warning("push rejected host=%s status=%s", host, code)
            self._record(device_id, code, failed=True)
        else:
            self.sent += 1
            self._record(device_id, code, ok=True)
        return "done"

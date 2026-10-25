"""Operator alerts through ntfy (optional): a short plain-text message to one topic when something needs the
administrator's attention. Messages are fixed texts: no user names, no message content, no addresses, no secrets.
Sending happens on a worker thread (a slow ntfy server never delays a request), and alerts are rate limited: each
kind has a minimum interval and there is a global cap per hour."""

import logging
import queue
import threading
import time
from collections.abc import Callable

import httpx

log = logging.getLogger("swahnchat.alerts")

MINUTE = 60.0
HOUR = 3600.0

# kind -> (message, minimum seconds between two alerts of that kind)
ALERTS: dict[str, tuple[str, float]] = {
    "low_disk": ("Low disk space on the data volume. Free some space or add storage.", 6 * HOUR),
    "maintenance_failed": ("Server maintenance failed. Check the backend log.", 6 * HOUR),
    "login_lockout": ("A login lockout was triggered.", 30 * MINUTE),
    "token_reuse": ("A refresh token was reused and its session was revoked.", 10 * MINUTE),
    "admin_granted": ("A new administrator was granted.", MINUTE),
}
MAX_PER_HOUR = 20


class AdminAlerts:
    def __init__(
        self,
        url: str,
        token: str = "",
        transport: httpx.BaseTransport | None = None,
        timeout: float = 5.0,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.url = url
        self._token = token
        self._clock = clock
        self._client = httpx.Client(transport=transport, timeout=timeout, follow_redirects=False) if url else None
        self._queue: queue.Queue[str | None] = queue.Queue()
        self._lock = threading.Lock()
        self._last: dict[str, float] = {}
        self._sent_at: list[float] = []
        self._thread: threading.Thread | None = None
        self.sent = 0

    @property
    def enabled(self) -> bool:
        return bool(self.url)

    def start(self) -> None:
        if self.enabled and self._thread is None:
            self._thread = threading.Thread(target=self._run, name="alerts", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        if self._thread is not None:
            self._queue.put(None)
            self._thread.join(timeout=10)
            self._thread = None
        if self._client is not None:
            self._client.close()

    def flush(self, timeout: float = 10.0) -> None:
        """Wait until queued alerts were sent (tests)."""
        done = threading.Event()
        threading.Thread(target=lambda: (self._queue.join(), done.set()), daemon=True).start()
        done.wait(timeout)

    def send(self, kind: str) -> bool:
        """Queue the alert of this kind unless alerts are off or the rate limits say no. Never raises."""
        if not self.enabled or kind not in ALERTS:
            return False
        interval = ALERTS[kind][1]
        now = self._clock()
        with self._lock:
            if kind in self._last and now - self._last[kind] < interval:
                return False
            self._sent_at = [t for t in self._sent_at if now - t < HOUR]
            if len(self._sent_at) >= MAX_PER_HOUR:
                return False
            self._last[kind] = now
            self._sent_at.append(now)
        self._queue.put(kind)
        return True

    def _run(self) -> None:
        while True:
            kind = self._queue.get()
            try:
                if kind is None:
                    return
                self._deliver(kind)
            except Exception:
                log.exception("alert worker error")
            finally:
                self._queue.task_done()

    def _deliver(self, kind: str) -> None:
        headers = {"Title": "Swahn Chat", "Tags": "warning", "Content-Type": "text/plain; charset=utf-8"}
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        try:
            resp = self._client.post(self.url, content=ALERTS[kind][0].encode(), headers=headers)  # type: ignore[union-attr]
        except httpx.HTTPError as exc:
            log.warning("admin alert %s not sent: %s", kind, type(exc).__name__)  # never the URL (it names the topic)
            return
        if resp.status_code >= 400:
            log.warning("admin alert %s rejected status=%s", kind, resp.status_code)
        else:
            self.sent += 1

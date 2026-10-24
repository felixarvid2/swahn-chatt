"""In-memory WebSocket registry (one process, so no broker). The socket is only a hint: events live in the DB."""

import asyncio
import logging
import threading
from dataclasses import dataclass, field

log = logging.getLogger("familychat.hub")


QUEUE_LIMIT = 256  # outgoing messages a connection may have waiting; a client that does not read is dropped


@dataclass(eq=False)
class Connection:
    device_id: str
    user_id: str
    queue: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=QUEUE_LIMIT))
    closed: asyncio.Event = field(default_factory=asyncio.Event)
    close_code: int = 1000
    close_reason: str = ""

    def offer(self, message: dict) -> None:
        """Queue a message (event loop only). A full queue means the peer stopped reading: close with 1008
        instead of buffering without bound. Events live in the database, so the client just syncs again."""
        try:
            self.queue.put_nowait(message)
        except asyncio.QueueFull:
            self.close_code = 1008
            self.closed.set()


class Hub:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._by_device: dict[str, set[Connection]] = {}
        self._loop: asyncio.AbstractEventLoop | None = None
        self._closing: tuple[int, str] | None = None

    # --- called on the event loop ---
    def register(self, conn: Connection) -> None:
        self._loop = asyncio.get_running_loop()
        with self._lock:
            self._by_device.setdefault(conn.device_id, set()).add(conn)
            closing = self._closing
        if closing:  # accepted while the server is shutting down: send it away at once
            conn.close_code, conn.close_reason = closing
            conn.closed.set()

    def unregister(self, conn: Connection) -> None:
        with self._lock:
            conns = self._by_device.get(conn.device_id)
            if conns:
                conns.discard(conn)
                if not conns:
                    del self._by_device[conn.device_id]

    # --- thread safe ---
    def is_connected(self, device_id: str) -> bool:
        with self._lock:
            return bool(self._by_device.get(device_id))

    def connected_count(self) -> int:
        with self._lock:
            return sum(len(c) for c in self._by_device.values())

    @property
    def loop(self) -> asyncio.AbstractEventLoop | None:
        return self._loop

    def connected_user_ids(self) -> set[str]:
        with self._lock:
            return {c.user_id for conns in self._by_device.values() for c in conns}

    def _connections(self, device_id: str) -> list[Connection]:
        with self._lock:
            return list(self._by_device.get(device_id, ()))

    def send(self, device_id: str, message: dict) -> bool:
        conns = self._connections(device_id)
        loop = self._loop
        if not conns or loop is None:
            return False
        for conn in conns:
            try:
                loop.call_soon_threadsafe(conn.offer, message)
            except RuntimeError:  # loop closed
                return False
        return True

    def kick(self, device_id: str, code: int = 4401) -> None:
        loop = self._loop
        for conn in self._connections(device_id):
            conn.close_code = code
            if loop is not None:
                try:
                    loop.call_soon_threadsafe(conn.closed.set)
                except RuntimeError:
                    pass

    async def close_all(self, code: int = 1001, reason: str = "server restarting", timeout: float = 3.0) -> None:
        """Server shutdown (event loop only): close every socket with `code` so clients reconnect at once, then
        give the connection handlers up to `timeout` seconds to send the close frames and unregister."""
        with self._lock:
            self._closing = (code, reason)
            conns = [c for group in self._by_device.values() for c in group]
        for conn in conns:
            conn.close_code, conn.close_reason = code, reason
            conn.closed.set()
        deadline = asyncio.get_running_loop().time() + timeout
        while self.connected_count() and asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(0.02)
        if self.connected_count():
            log.warning("%d websocket(s) still open after the shutdown grace period", self.connected_count())

    def user_devices_connected(self, user_ids: set[str]) -> list[Connection]:
        with self._lock:
            return [c for conns in self._by_device.values() for c in conns if c.user_id in user_ids]

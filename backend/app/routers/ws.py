"""WebSocket: a hint channel plus typing indicators. Events themselves are only ever read via GET /sync."""

import asyncio
import contextlib
import logging
import time
from collections import deque

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import select

from app.deps import Principal, check_session, touch_last_seen
from app.errors import ApiError
from app.hub import Connection
from app.models import ConversationMember
from app.schemas import UUID_RE
from app.security import AccessClaims, decode_access_token
from app.services.sync import latest_cursor

log = logging.getLogger("familychat.ws")
router = APIRouter()

# Inbound frame limits per connection (sliding windows, seconds).
FRAME_WINDOW_S = 10.0
FRAMES_HARD_LIMIT = 100  # any frame, valid or not: beyond this the socket is closed with 1008
TYPING_LIMIT = 20  # typing frames processed per window; extra ones are dropped silently
PING_LIMIT = 5  # pings answered per window; extra ones are dropped silently
MEMBERSHIP_TTL_S = 15.0  # how long a conversation's member list is reused for typing frames
MEMBERSHIP_CACHE_MAX = 64


class Window:
    """Sliding-window counter for one kind of frame on one connection."""

    def __init__(self, limit: int, window: float = FRAME_WINDOW_S):
        self.limit, self.window = limit, window
        self._hits: deque[float] = deque()

    def allow(self, now: float) -> bool:
        while self._hits and self._hits[0] <= now - self.window:
            self._hits.popleft()
        if len(self._hits) >= self.limit:
            return False
        self._hits.append(now)
        return True


def _token(ws: WebSocket) -> str | None:
    header = ws.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() == "bearer" and token.strip():
        return token.strip()
    return ws.query_params.get("access_token")


def _hello_and_members(app, p: Principal) -> int:
    with app.state.read_session_factory() as db:
        return latest_cursor(db, p.device_id)


def _typing_targets(app, p: Principal, conversation_id: str) -> set[str] | None:
    with app.state.read_session_factory() as db:
        users = set(
            db.scalars(select(ConversationMember.user_id).where(ConversationMember.conversation_id == conversation_id))
        )
    if p.user_id not in users:
        return None
    users.discard(p.user_id)
    return users


@router.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    app = ws.app
    await ws.accept()
    token = _token(ws)
    try:
        if not token:
            raise ApiError(401, "unauthorized")
        claims = decode_access_token(app.state.settings, token)
        p, stale = await run_in_threadpool(check_session, app, claims)
        if stale:
            await run_in_threadpool(touch_last_seen, app, p.device_id)
    except ApiError:
        await ws.close(code=4401)
        return
    hub = app.state.hub
    conn = Connection(device_id=p.device_id, user_id=p.user_id)
    hub.register(conn)
    try:
        # A revocation that committed between check_session() and register() found no socket to kick, so look
        # again now that this connection can be kicked.
        try:
            await run_in_threadpool(check_session, app, claims)
        except ApiError:
            await ws.close(code=4401)
            return
        cursor = await run_in_threadpool(_hello_and_members, app, p)
        await ws.send_json({"type": "hello", "cursor": cursor})
        interval = app.state.settings.ws_revalidate_seconds
        killer = asyncio.create_task(conn.closed.wait())
        tasks = {
            asyncio.create_task(_writer(ws, conn)),
            asyncio.create_task(_reader(ws, conn, p)),
            asyncio.create_task(_revalidate(app, conn, claims, interval)),
            killer,
        }
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()
        for t in pending:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await t
        for t in done:
            if not t.cancelled() and t.exception() and not isinstance(t.exception(), WebSocketDisconnect):
                log.warning("websocket task failed: %s", type(t.exception()).__name__)
        if killer in done:
            with contextlib.suppress(Exception):
                await ws.close(code=conn.close_code)
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("websocket error")
    finally:
        hub.unregister(conn)


async def _revalidate(app, conn: Connection, claims: AccessClaims, interval: float) -> None:
    """Close the socket (4401) soon after its session, device or user is revoked or disabled, also when that was
    done by another process (CLI), which cannot reach this process's hub. Not the token's own expiry: a socket
    outlives its 15 minute access token by design."""
    while True:
        await asyncio.sleep(interval)
        try:
            await run_in_threadpool(check_session, app, claims)
        except ApiError:
            conn.close_code = 4401
            conn.closed.set()
            return
        except Exception:  # database trouble must not kick healthy clients
            log.warning("websocket session re-validation failed", exc_info=True)


async def _writer(ws: WebSocket, conn: Connection) -> None:
    while True:
        msg = await conn.queue.get()
        await ws.send_json(msg)


async def _reader(ws: WebSocket, conn: Connection, p: Principal) -> None:
    hub = ws.app.state.hub
    frames, typing, pings = Window(FRAMES_HARD_LIMIT), Window(TYPING_LIMIT), Window(PING_LIMIT)
    members: dict[str, tuple[float, set[str] | None]] = {}
    while True:
        try:
            data = await ws.receive_json()
        except ValueError:
            data = None
        now = time.monotonic()
        if not frames.allow(now):
            conn.close_code = 1008
            conn.closed.set()
            return
        if not isinstance(data, dict):
            continue
        kind = data.get("type")
        if kind == "ping":
            if pings.allow(now):
                conn.offer({"type": "pong"})
        elif kind == "typing":
            conv_id, state = data.get("conversation_id"), data.get("state")
            if state not in ("start", "stop") or not isinstance(conv_id, str) or not UUID_RE.match(conv_id):
                continue
            if not typing.allow(now):
                continue
            cached = members.get(conv_id)
            if cached is None or cached[0] <= now:
                targets = await run_in_threadpool(_typing_targets, ws.app, p, conv_id)
                if len(members) >= MEMBERSHIP_CACHE_MAX:
                    members.clear()
                members[conv_id] = (now + MEMBERSHIP_TTL_S, targets)  # misses are cached as well
            else:
                targets = cached[1]
            if not targets:
                continue
            note = {"type": "typing", "conversation_id": conv_id, "user_id": p.user_id, "state": state}
            for other in hub.user_devices_connected(targets):
                hub.send(other.device_id, note)

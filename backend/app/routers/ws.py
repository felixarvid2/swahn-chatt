"""WebSocket: a hint channel plus typing indicators. Events themselves are only ever read via GET /sync."""

import asyncio
import contextlib
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import select

from app.deps import Principal, authenticate
from app.errors import ApiError
from app.hub import Connection
from app.models import ConversationMember
from app.schemas import UUID_RE
from app.services.sync import latest_cursor

log = logging.getLogger("familychat.ws")
router = APIRouter()


def _token(ws: WebSocket) -> str | None:
    header = ws.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() == "bearer" and token.strip():
        return token.strip()
    return ws.query_params.get("access_token")


def _hello_and_members(app, p: Principal) -> int:
    with app.state.session_factory() as db:
        return latest_cursor(db, p.device_id)


def _typing_targets(app, p: Principal, conversation_id: str) -> set[str] | None:
    with app.state.session_factory() as db:
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
        p = await run_in_threadpool(authenticate, app, token)
    except ApiError:
        await ws.close(code=4401)
        return
    hub = app.state.hub
    conn = Connection(device_id=p.device_id, user_id=p.user_id)
    hub.register(conn)
    try:
        cursor = await run_in_threadpool(_hello_and_members, app, p)
        await ws.send_json({"type": "hello", "cursor": cursor})
        writer = asyncio.create_task(_writer(ws, conn))
        reader = asyncio.create_task(_reader(ws, conn, p))
        killer = asyncio.create_task(conn.closed.wait())
        done, pending = await asyncio.wait({writer, reader, killer}, return_when=asyncio.FIRST_COMPLETED)
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


async def _writer(ws: WebSocket, conn: Connection) -> None:
    while True:
        msg = await conn.queue.get()
        await ws.send_json(msg)


async def _reader(ws: WebSocket, conn: Connection, p: Principal) -> None:
    hub = ws.app.state.hub
    while True:
        try:
            data = await ws.receive_json()
        except ValueError:
            continue
        if not isinstance(data, dict):
            continue
        kind = data.get("type")
        if kind == "ping":
            conn.queue.put_nowait({"type": "pong"})
        elif kind == "typing":
            conv_id, state = data.get("conversation_id"), data.get("state")
            if state not in ("start", "stop") or not isinstance(conv_id, str) or not UUID_RE.match(conv_id):
                continue
            targets = await run_in_threadpool(_typing_targets, ws.app, p, conv_id)
            if not targets:
                continue
            note = {"type": "typing", "conversation_id": conv_id, "user_id": p.user_id, "state": state}
            for other in hub.user_devices_connected(targets):
                hub.send(other.device_id, note)

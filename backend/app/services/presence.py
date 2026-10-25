"""Opt-in presence ("online" / "last seen"), enforced here and nowhere else.

Only users with `users.share_presence` set are ever tracked, stored or announced. For everybody else nothing is
written and no frame is sent; `GET /users` shows `online`/`last_seen_at` as null for them. Announcements go to the
connected devices of users who share a conversation with the person, as `{"type":"presence", ...}` frames, and are
debounced: the first change goes out at once, further changes of the same user at most every `debounce` seconds (the
state current at that moment is sent, and only if it differs from what was last announced), so a flaky connection
does not flood the contacts. Event loop only (one process, like the hub)."""

import asyncio
import contextlib
import logging
import time

from fastapi.concurrency import run_in_threadpool
from sqlalchemy import select, update

from app import clock
from app.models import User
from app.services.events import co_member_user_ids

log = logging.getLogger("swahnchat.presence")


def frame(user_id: str, online: bool, last_seen_at: int | None) -> dict:
    return {"type": "presence", "user_id": user_id, "online": online, "last_seen_at": last_seen_at}


class Presence:
    def __init__(self, app, debounce: float):
        self.app = app
        self.debounce = debounce
        self._announced: dict[str, bool] = {}  # last state sent to contacts, per sharing user
        self._last_sent: dict[str, float] = {}
        self._timers: dict[str, asyncio.Task] = {}
        self._tasks: set[asyncio.Task] = set()

    # --- called by the WebSocket endpoint -------------------------------------------------------------------
    def fire(self, user_id: str, exclude=None) -> None:
        """Run changed() as a task of its own: the WebSocket handler may be cancelled while it is closing (server
        shutdown, test clients), and the bookkeeping must still happen."""
        task = asyncio.get_running_loop().create_task(self._guarded(user_id, exclude))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _guarded(self, user_id: str, exclude=None) -> None:
        try:
            await self.changed(user_id, exclude)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.warning("presence update failed", exc_info=True)

    async def changed(self, user_id: str, exclude=None) -> None:
        """A connection of `user_id` opened (exclude=None) or is closing (`exclude` = that connection)."""
        if not await run_in_threadpool(self._opted_in, user_id):
            return  # not sharing: nothing is recorded, nothing is sent
        online = self._online(user_id, exclude)
        if online or exclude is not None:
            await run_in_threadpool(self._store_last_seen, user_id)
        await self._schedule(user_id, exclude)

    async def retract(self, user_id: str) -> None:
        """Sharing was switched off: stop tracking, and tell contacts once that the status is unknown now (the same
        thing they see for any user who does not share; it carries no presence information)."""
        timer = self._timers.pop(user_id, None)
        if timer:
            timer.cancel()
        was = self._announced.pop(user_id, None)
        self._last_sent.pop(user_id, None)
        if was is not None:
            await self._send(user_id, frame(user_id, False, None))

    def stop(self) -> None:
        for t in [*self._timers.values(), *self._tasks]:
            t.cancel()
        self._timers.clear()

    # --- internals -------------------------------------------------------------------------------------------
    def _online(self, user_id: str, exclude=None) -> bool:
        hub = self.app.state.hub
        return any(c is not exclude for c in hub.user_devices_connected({user_id}))

    def _opted_in(self, user_id: str) -> bool:
        with self.app.state.read_session_factory() as db:
            return bool(db.scalar(select(User.share_presence).where(User.id == user_id, User.disabled_at.is_(None))))

    def _store_last_seen(self, user_id: str) -> None:
        with self.app.state.session_factory() as db:
            db.execute(
                update(User)
                .where(User.id == user_id, User.share_presence.is_(True))
                .values(presence_last_seen_at=clock.now_ms())
            )
            db.commit()

    def _last_seen(self, user_id: str) -> int | None:
        with self.app.state.read_session_factory() as db:
            return db.scalar(select(User.presence_last_seen_at).where(User.id == user_id))

    async def _schedule(self, user_id: str, exclude=None) -> None:
        if user_id in self._timers:
            return  # the pending timer announces whatever is current when it fires
        wait = self._last_sent.get(user_id, -1e18) + self.debounce - time.monotonic()
        if wait <= 0:
            await self._announce(user_id, exclude)
        else:
            self._timers[user_id] = asyncio.create_task(self._later(user_id, wait))

    async def _later(self, user_id: str, wait: float) -> None:
        try:
            await asyncio.sleep(wait)
            self._timers.pop(user_id, None)
            await self._announce(user_id)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("presence announcement failed")
        finally:
            if self._timers.get(user_id) is asyncio.current_task():
                self._timers.pop(user_id, None)

    async def _announce(self, user_id: str, exclude=None) -> None:
        if not await run_in_threadpool(self._opted_in, user_id):
            return
        online = self._online(user_id, exclude)
        if online == self._announced.get(user_id, False):
            return
        self._announced[user_id] = online
        self._last_sent[user_id] = time.monotonic()
        last_seen = clock.now_ms() if online else await run_in_threadpool(self._last_seen, user_id)
        await self._send(user_id, frame(user_id, online, last_seen))

    async def _send(self, user_id: str, message: dict) -> None:
        hub = self.app.state.hub

        def targets() -> set[str]:
            with self.app.state.read_session_factory() as db:
                return co_member_user_ids(db, user_id) - {user_id}

        users = await run_in_threadpool(targets)
        for conn in hub.user_devices_connected(users):
            hub.send(conn.device_id, message)


def settings_changed(app, user_id: str, enabled: bool) -> None:
    """From a request thread, after the commit: start or stop announcing. Nothing to do without an event loop (no
    socket was ever opened, so nobody is online and nothing was announced)."""
    presence: Presence = app.state.presence
    loop = app.state.hub.loop
    if loop is None or loop.is_closed():
        return

    async def run() -> None:
        try:
            await (presence.changed(user_id) if enabled else presence.retract(user_id))
        except Exception:
            log.warning("presence update failed", exc_info=True)

    with contextlib.suppress(RuntimeError):
        asyncio.run_coroutine_threadsafe(run(), loop)

"""Engine and session factory. SQLite is tuned (WAL, busy timeout, foreign keys); nothing else is SQLite-only."""

import logging
from collections.abc import Callable
from typing import Any

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import NullPool

from app.config import Settings

log = logging.getLogger("familychat.db")


def make_engine(url: str) -> Engine:
    if url.startswith("sqlite"):
        kwargs: dict[str, Any] = {"connect_args": {"check_same_thread": False, "timeout": 10}}
        if ":memory:" in url:
            raise ValueError("in-memory SQLite is not supported (multiple connections)")
        engine = create_engine(url, pool_size=20, max_overflow=30, **kwargs)

        @event.listens_for(engine, "connect")
        def _on_connect(dbapi_conn, _record):
            # We drive transactions ourselves (see "begin" below).
            dbapi_conn.isolation_level = None
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA journal_mode=WAL")
            cur.execute("PRAGMA synchronous=NORMAL")
            cur.execute("PRAGMA busy_timeout=10000")
            cur.execute("PRAGMA foreign_keys=ON")
            cur.close()

        @event.listens_for(engine, "begin")
        def _on_begin(conn):
            # Take the write lock up front: a deferred read->write upgrade can fail with SQLITE_BUSY
            # without honouring busy_timeout. Writers queue instead (fine for a family server).
            conn.exec_driver_sql("BEGIN IMMEDIATE")

        return engine
    return create_engine(url, pool_pre_ping=True)


def make_session_factory(engine: Engine, info: dict[str, Any] | None = None) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, autoflush=True, info={} if info is None else info)


@event.listens_for(Session, "after_commit")
def _after_commit(session: Session) -> None:
    pending = session.info.pop("pending_notify", None)
    kicks = session.info.pop("pending_kick", None)
    dispatcher = session.info.get("dispatcher")
    if dispatcher and (pending or kicks):
        try:
            dispatcher.dispatch(pending or {}, kicks or set())
        except Exception:  # never let a notification failure fail a committed request
            log.exception("dispatch failed")


@event.listens_for(Session, "after_rollback")
def _after_rollback(session: Session) -> None:
    session.info.pop("pending_notify", None)
    session.info.pop("pending_kick", None)


def session_factory_for(settings: Settings) -> Callable[[], Session]:
    return make_session_factory(make_engine(settings.database_url))


__all__ = ["NullPool", "make_engine", "make_session_factory"]

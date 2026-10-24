"""Engine and session factory. SQLite is tuned (WAL, busy timeout, foreign keys). On PostgreSQL every writing
transaction takes one advisory lock first, which gives the same guarantee SQLite's BEGIN IMMEDIATE does: writers
queue, so event `seq` values become visible in commit order (see docs/ARCHITECTURE.md, "Sync cursor")."""

import logging
from collections.abc import Callable
from typing import Any

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import NullPool

from app.config import Settings

log = logging.getLogger("familychat.db")

READ_ONLY_OPTION = "familychat_read_only"
# pg_advisory_xact_lock key shared by all writing transactions; held until commit or rollback.
PG_WRITE_LOCK_KEY = 0x46414D43  # "FAMC"
PG_LOCK_TIMEOUT = "10s"  # like SQLite's busy timeout: fail instead of waiting forever


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
            # Strictly read-only work opts out (make_read_session_factory): a deferred BEGIN takes no lock in
            # WAL mode, so reads never wait for, or hold up, a writer.
            if conn.get_execution_options().get(READ_ONLY_OPTION):
                conn.exec_driver_sql("BEGIN")
            else:
                conn.exec_driver_sql("BEGIN IMMEDIATE")

        return engine
    engine = create_engine(url, pool_pre_ping=True)
    if engine.dialect.name == "postgresql":

        @event.listens_for(engine, "begin")
        def _pg_on_begin(conn):
            # The events table's seq comes from a sequence, which hands out numbers in insert order, not commit
            # order: with concurrent writers a device could read seq 11, move its cursor past it, and never see 10
            # when that transaction commits a moment later. Serialising writers makes commit order = seq order.
            # Strictly read-only work opts out, exactly as on SQLite.
            if not conn.get_execution_options().get(READ_ONLY_OPTION):
                conn.exec_driver_sql(f"SET LOCAL lock_timeout = '{PG_LOCK_TIMEOUT}'")
                conn.exec_driver_sql(f"SELECT pg_advisory_xact_lock({PG_WRITE_LOCK_KEY})")

    return engine


def make_session_factory(engine: Engine, info: dict[str, Any] | None = None) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, autoflush=True, info={} if info is None else info)


def make_read_session_factory(engine: Engine) -> sessionmaker[Session]:
    """Sessions for code that never writes: on SQLite they start a deferred transaction (no write lock).
    Flushing any change through such a session is a bug and raises."""
    factory = sessionmaker(
        bind=engine.execution_options(**{READ_ONLY_OPTION: True}), expire_on_commit=False, autoflush=False
    )

    @event.listens_for(factory, "before_flush")
    def _no_writes(session, _ctx, _instances):
        if session.new or session.dirty or session.deleted:
            raise RuntimeError("read-only session must not modify the database")

    return factory


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


__all__ = ["NullPool", "make_engine", "make_read_session_factory", "make_session_factory"]

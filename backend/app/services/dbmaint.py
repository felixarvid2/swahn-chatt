"""Weekly database maintenance, run by the hourly maintenance loop in its worker thread (never on a request).

SQLite: `PRAGMA optimize`, `ANALYZE`, a WAL checkpoint (TRUNCATE) and, only when more than 20 % of the file's pages are
free and the disk has room for the copy, `VACUUM`. VACUUM rewrites the whole file under the write lock, so requests
that write wait until it is done (seconds for a family sized database), and it is skipped while the server is busy:
it only runs when no request arrived for a minute. A skipped VACUUM is retried at the next hourly tick.
PostgreSQL: `ANALYZE` only (autovacuum does the rest).
The result is stored in `server_state` (shown by GET /admin/stats) and logged."""

import json
import logging
import shutil
import sqlite3
import time
from collections.abc import Callable

from sqlalchemy import Engine, select, text
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.db import READ_ONLY_OPTION
from app.models import ServerState

log = logging.getLogger("familychat.dbmaint")

STATE_KEY = "db_maintenance"
DAY_MS = 86_400_000
VACUUM_FREELIST_RATIO = 0.20
IDLE_SECONDS = 60.0
DISK_MARGIN = 1.25  # VACUUM writes a full copy (and, in WAL mode, a journal): need this multiple of the file size
BUSY_TIMEOUT_MS = 5000  # never wait long for a lock: skip and try again next hour


def last_run(db: Session) -> dict | None:
    row = db.scalar(select(ServerState.value).where(ServerState.key == STATE_KEY))
    return dict(row) if row else None


def is_due(db: Session, settings: Settings) -> bool:
    if settings.db_maintenance_days <= 0:
        return False
    state = last_run(db)
    if state is None or not state.get("ok") or state.get("vacuum") == "busy":
        return True  # a failed run, or a VACUUM that had to wait, is retried at the next hourly tick
    return clock.now_ms() - state["at"] >= settings.db_maintenance_days * DAY_MS


def _store(db: Session, result: dict) -> None:
    row = db.get(ServerState, STATE_KEY)
    now = clock.now_ms()
    if row is None:
        db.add(ServerState(key=STATE_KEY, value=result, updated_at=now))
    else:
        row.value = result
        row.updated_at = now
    db.commit()


def _sqlite(settings: Settings, idle: Callable[[], bool]) -> dict:
    path = settings.sqlite_path
    steps: list[str] = []
    con = sqlite3.connect(path, timeout=BUSY_TIMEOUT_MS / 1000, isolation_level=None)
    try:
        con.execute(f"PRAGMA busy_timeout={BUSY_TIMEOUT_MS}")
        con.execute("PRAGMA optimize")
        steps.append("optimize")
        con.execute("ANALYZE")
        steps.append("analyze")
        con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        steps.append("wal_checkpoint")
        pages = con.execute("PRAGMA page_count").fetchone()[0]
        free = con.execute("PRAGMA freelist_count").fetchone()[0]
        ratio = free / pages if pages else 0.0
        size = pages * con.execute("PRAGMA page_size").fetchone()[0]
        if ratio <= VACUUM_FREELIST_RATIO:
            vacuum = "not_needed"
        elif shutil.disk_usage(path.parent).free < size * DISK_MARGIN:
            vacuum = "disk_space"
        elif not idle():
            vacuum = "busy"
        else:
            con.execute("VACUUM")
            con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            steps.append("vacuum")
            vacuum = "done"
        return {"steps": steps, "vacuum": vacuum, "freelist_ratio": round(ratio, 4)}
    finally:
        con.close()


def _postgres(engine: Engine) -> dict:
    # No write lock needed (the read-only option skips the advisory lock); ANALYZE is fine in autocommit.
    with engine.execution_options(**{READ_ONLY_OPTION: True}).connect() as conn:
        conn.execution_options(isolation_level="AUTOCOMMIT").execute(text("ANALYZE"))
    return {"steps": ["analyze"], "vacuum": "n/a", "freelist_ratio": None}


def run(db: Session, engine: Engine, settings: Settings, idle: Callable[[], bool] = lambda: True) -> dict:
    """Run the maintenance now and store the result. Raises nothing for a failed step: the result says so
    (`ok` false, `error` the exception type) so the caller can alert."""
    started = time.monotonic()
    db.rollback()  # the caller's session must not hold the write lock while the maintenance connection works
    result: dict
    try:
        body = _sqlite(settings, idle) if settings.is_sqlite else _postgres(engine)
        result = {"ok": True, "error": None, **body}
    except Exception as exc:
        log.exception("database maintenance failed")
        result = {"ok": False, "error": type(exc).__name__, "steps": [], "vacuum": "n/a", "freelist_ratio": None}
    result["dialect"] = "sqlite" if settings.is_sqlite else engine.dialect.name
    result["at"] = clock.now_ms()
    result["duration_ms"] = int((time.monotonic() - started) * 1000)
    _store(db, result)
    log.info("database maintenance %s", json.dumps(result))
    return result

"""Weekly database maintenance: SQLite optimize/ANALYZE/checkpoint and a conditional VACUUM, PostgreSQL ANALYZE."""

import sqlite3
import threading
from collections import namedtuple

import pytest

from app import clock
from app.services import dbmaint

DAY_MS = 86_400_000


def run(app, **kw):
    with app.state.session_factory() as db:
        return dbmaint.run(db, app.state.engine, app.state.settings, **kw)


def make_free_pages(app):
    """Grow the database with a junk table and drop it again: lots of free pages inside the file."""
    con = sqlite3.connect(app.state.settings.sqlite_path)
    con.execute("CREATE TABLE junk (x BLOB)")
    con.executemany("INSERT INTO junk VALUES (?)", [(b"x" * 4000,) for _ in range(1500)])
    con.commit()
    con.execute("DROP TABLE junk")
    con.commit()
    con.close()


def test_run_records_the_result_and_admin_stats_shows_it(app, admin):
    assert admin.get("/admin/stats").json()["db_maintenance"] is None
    result = run(app)
    assert result["ok"] is True and result["error"] is None
    assert result["steps"][:2] == (["optimize", "analyze"] if app.state.settings.is_sqlite else ["analyze"])
    shown = admin.get("/admin/stats").json()["db_maintenance"]
    assert shown["at"] == result["at"] and shown["ok"] is True
    assert shown["dialect"] == ("sqlite" if app.state.settings.is_sqlite else "postgresql")
    assert shown["vacuum"] in ("not_needed", "n/a")
    run(app)  # a second run replaces the stored row instead of adding one
    with app.state.session_factory() as db:
        assert dbmaint.last_run(db)["at"] >= result["at"]


@pytest.mark.sqlite_only
def test_sqlite_steps_checkpoint_truncates_the_wal(app, alice, bob, factory):
    factory.direct(alice, bob)
    wal = app.state.settings.sqlite_path.with_name("swahnchat.db-wal")
    assert wal.exists() and wal.stat().st_size > 0
    body = dbmaint._sqlite(app.state.settings, lambda: True)  # before the result row is written (that grows the WAL)
    assert body["steps"] == ["optimize", "analyze", "wal_checkpoint"]
    assert body["vacuum"] == "not_needed"
    assert not wal.exists() or wal.stat().st_size == 0
    assert run(app)["steps"] == ["optimize", "analyze", "wal_checkpoint"]


@pytest.mark.sqlite_only
def test_vacuum_runs_only_above_twenty_percent_free_pages(app):
    path = app.state.settings.sqlite_path
    make_free_pages(app)
    big = path.stat().st_size
    result = run(app)
    assert result["vacuum"] == "done" and "vacuum" in result["steps"]
    assert result["freelist_ratio"] > 0.2
    assert path.stat().st_size < big / 2
    con = sqlite3.connect(path)
    assert con.execute("PRAGMA freelist_count").fetchone()[0] == 0
    con.close()
    assert run(app)["vacuum"] == "not_needed"


@pytest.mark.sqlite_only
def test_vacuum_waits_while_the_server_is_busy_and_is_retried(app):
    make_free_pages(app)
    busy = run(app, idle=lambda: False)
    assert busy["vacuum"] == "busy" and "vacuum" not in busy["steps"]
    with app.state.session_factory() as db:
        assert dbmaint.is_due(db, app.state.settings)  # retried at the next hourly tick, not next week
    assert run(app, idle=lambda: True)["vacuum"] == "done"
    with app.state.session_factory() as db:
        assert not dbmaint.is_due(db, app.state.settings)


@pytest.mark.sqlite_only
def test_vacuum_is_skipped_without_enough_free_disk(app, monkeypatch):
    make_free_pages(app)
    usage = namedtuple("usage", "total used free")
    monkeypatch.setattr(dbmaint.shutil, "disk_usage", lambda _p: usage(10**9, 10**9, 1024))
    result = run(app)
    assert result["vacuum"] == "disk_space" and result["ok"] is True


def test_due_once_a_week_and_never_when_disabled(app, monkeypatch):
    settings = app.state.settings
    with app.state.session_factory() as db:
        assert dbmaint.is_due(db, settings)  # never ran
    run(app)
    now = clock.now_ms()
    with app.state.session_factory() as db:
        assert not dbmaint.is_due(db, settings)
        monkeypatch.setattr(clock, "now_ms", lambda: now + 6 * DAY_MS)
        assert not dbmaint.is_due(db, settings)
        monkeypatch.setattr(clock, "now_ms", lambda: now + 7 * DAY_MS + 1)
        assert dbmaint.is_due(db, settings)
        off = settings.model_copy(update={"db_maintenance_days": 0})
        assert not dbmaint.is_due(db, off)


def test_a_failing_step_is_recorded_not_raised(app, monkeypatch):
    def boom(*_a, **_k):
        raise RuntimeError("disk on fire")

    monkeypatch.setattr(dbmaint, "_sqlite", boom)
    monkeypatch.setattr(dbmaint, "_postgres", boom)
    result = run(app)
    assert result["ok"] is False and result["error"] == "RuntimeError"
    with app.state.session_factory() as db:
        assert dbmaint.last_run(db)["ok"] is False
        assert dbmaint.is_due(db, app.state.settings)  # a failed run is retried at the next hourly tick


def test_requests_keep_working_while_maintenance_runs(app, alice, bob, factory):
    cid = factory.direct(alice, bob)
    errors, done = [], threading.Event()

    def worker():
        try:
            for _ in range(3):
                run(app)
        except BaseException as exc:
            errors.append(exc)
        finally:
            done.set()

    t = threading.Thread(target=worker)
    t.start()
    while not done.is_set():
        assert alice.send(cid, "hello").status_code == 201
    t.join()
    assert not errors

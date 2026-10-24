"""PostgreSQL specifics. Skipped unless FAMILYCHAT_TEST_DATABASE_URL points at a PostgreSQL server.

The sync cursor is a plain number: a device that has read event N moves on to `seq > N`. That is only safe when
event seq values become visible in order. SQLite guarantees it (one writer at a time, BEGIN IMMEDIATE); on
PostgreSQL a sequence hands out numbers at insert time, so make_engine serialises writing transactions with an
advisory lock (app/db.py)."""

import threading
import time

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import NullPool

from app.services.events import emit

pytestmark = pytest.mark.pg_only


def hold_write_transaction(app, device_id, tag):
    """A writer that emitted an event and keeps its transaction open until `release` is set."""
    emitted, release, done = threading.Event(), threading.Event(), threading.Event()
    errors = []

    def run():
        try:
            with app.state.session_factory() as db:
                emit(db, [device_id], "devices_changed", {"user_id": tag})
                emitted.set()
                assert release.wait(20)
                db.commit()
        except BaseException as exc:  # reported by the test
            errors.append(exc)
        finally:
            emitted.set()
            done.set()

    t = threading.Thread(target=run, daemon=True)
    t.start()
    return emitted, release, done, t, errors


def test_event_seq_becomes_visible_in_commit_order(app, alice):
    emitted, release, done, t1, errors = hold_write_transaction(app, alice.device_id, "first")
    assert emitted.wait(10)
    # A second writer starts while the first transaction is still open: it has to wait for it.
    second_emitted, second_release, second_done, t2, second_errors = hold_write_transaction(
        app, alice.device_id, "second"
    )
    second_release.set()
    assert not second_emitted.wait(0.7), "the second writer must queue behind the first"
    assert alice.events("devices_changed") == []  # nothing visible yet, so no cursor can jump over a gap
    release.set()
    assert done.wait(10) and second_done.wait(10)
    t1.join(5), t2.join(5)
    assert errors == [] and second_errors == []
    seen = [(e["seq"], e["data"]["user_id"]) for e in alice.events("devices_changed")]
    assert [tag for _seq, tag in seen] == ["first", "second"]
    assert seen[0][0] < seen[1][0]


def test_readers_and_read_only_endpoints_do_not_wait_for_a_writer(app, alice):
    emitted, release, done, t, errors = hold_write_transaction(app, alice.device_id, "slow")
    assert emitted.wait(10)
    try:
        started = time.monotonic()
        assert alice.get("/users/me").status_code == 200
        assert alice.get("/conversations").status_code == 200
        assert alice.get("/sync").status_code == 200
        assert time.monotonic() - started < 3
    finally:
        release.set()
        done.wait(10)
        t.join(5)
    assert errors == []


def test_without_the_lock_a_cursor_would_skip_an_event(app, alice):
    """Documents why the lock exists: with a plain engine, seq 'a' is allocated first but 'b' commits first."""
    engine = create_engine(app.state.settings.database_url, poolclass=NullPool)
    insert = text(
        "INSERT INTO events (device_id, type, payload, created_at) "
        "VALUES (:d, 'devices_changed', '{}', 1) RETURNING seq"
    )
    try:
        with engine.connect() as c1, engine.connect() as c2:
            a = c1.execute(insert, {"d": alice.device_id}).scalar()
            b = c2.execute(insert, {"d": alice.device_id}).scalar()
            c2.commit()
            assert a < b
            visible = [e["seq"] for e in alice.events("devices_changed")]
            assert visible == [b]  # a device whose cursor is now b never sees a
            c1.commit()
    finally:
        engine.dispose()


def test_message_seq_counter_is_gap_free_and_distinct_under_concurrency(factory, alice, bob):
    cid = factory.direct(alice, bob)
    results = []

    def send(i):
        results.append(alice.send(cid, f"m{i}").json()["message"]["seq"])

    threads = [threading.Thread(target=send, args=(i,)) for i in range(12)]
    [t.start() for t in threads]
    [t.join(30) for t in threads]
    assert sorted(results) == list(range(min(results), min(results) + 12))


def test_json_columns_round_trip(app, alice, admin):
    assert alice.post("/admin/invitations", {"note": "x"}).status_code == 403
    admin.post("/admin/invitations", {"note": "j", "is_admin": True})
    rows = admin.get("/admin/audit?limit=5").json()
    created = next(r for r in rows if r["action"] == "invitation_created")
    assert created["details"] == {"is_admin": True, "hours": 72}

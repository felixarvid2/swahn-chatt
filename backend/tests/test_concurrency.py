"""Argon2 never runs inside a database transaction, hashing is capped, and the races the write lock used to hide
(invite redeem, refresh rotation, password change) are still safe."""

import sqlite3
import threading
import time

import pytest
from sqlalchemy import select, text, update

import app.routers.auth as auth_router
import app.routers.users as users_router
import app.security as security
from app.models import Device, Invitation, User
from tests.conftest import PASSWORD, make_settings

WAIT = 15


def other_connection(app) -> sqlite3.Connection:
    con = sqlite3.connect(app.state.settings.sqlite_path, timeout=0.2, isolation_level=None)
    con.execute("PRAGMA busy_timeout=200")
    return con


def test_read_sessions_take_no_write_lock_but_normal_sessions_do(app, alice):
    with app.state.read_session_factory() as db:
        db.execute(select(User.id)).all()
        assert db.in_transaction()
        con = other_connection(app)
        con.execute("BEGIN IMMEDIATE")  # would raise "database is locked" if the reader held the write lock
        con.execute("ROLLBACK")
        con.close()
    with app.state.session_factory() as db:
        db.execute(select(User.id)).all()
        con = other_connection(app)
        with pytest.raises(sqlite3.OperationalError, match="locked"):
            con.execute("BEGIN IMMEDIATE")
        con.close()


def test_read_sessions_refuse_to_write(app, alice):
    with app.state.read_session_factory() as db:
        db.get(User, alice.id).display_name = "changed"
        with pytest.raises(RuntimeError, match="read-only"):
            db.flush()


def test_authentication_holds_no_write_lock_during_the_request(app, alice):
    """The old authenticate() ran BEGIN IMMEDIATE for every request."""
    con = other_connection(app)
    con.execute("BEGIN IMMEDIATE")  # a writer is busy...
    try:
        r = alice.get("/users/me")  # ...reads still authenticate and work
        assert r.status_code == 200
    finally:
        con.execute("ROLLBACK")
        con.close()


class Parked:
    """Replace a hashing function so it blocks (like a slow Argon2) until released."""

    def __init__(self, monkeypatch, module, name):
        self.entered = threading.Event()
        self.release = threading.Event()
        original = getattr(module, name)

        def parked(*a, **kw):
            self.entered.set()
            assert self.release.wait(WAIT)
            return original(*a, **kw)

        monkeypatch.setattr(module, name, parked)


def run_in_thread(fn):
    out = {}

    def target():
        out["r"] = fn()

    t = threading.Thread(target=target)
    t.start()
    return t, out


def assert_writes_proceed(actor):
    start = time.monotonic()
    r = actor.patch("/users/me", {"display_name": "Busy"})
    assert r.status_code == 200
    assert time.monotonic() - start < 3  # busy_timeout is 10 s: a held write lock would show up as a long wait


def test_login_does_not_hold_the_write_lock_while_hashing(monkeypatch, factory, client, alice, bob):
    parked = Parked(monkeypatch, auth_router, "verify_password")
    body = {"username": "alice", "password": PASSWORD, "device_name": "tablet"}
    t, out = run_in_thread(lambda: client.post("/api/v1/auth/login", json=body))
    assert parked.entered.wait(WAIT)
    try:
        assert_writes_proceed(bob)
        assert bob.get("/users/me").status_code == 200
    finally:
        parked.release.set()
        t.join(WAIT)
    assert out["r"].status_code == 200


def test_failed_logins_do_not_hold_the_write_lock_either(monkeypatch, client, alice, bob):
    parked = Parked(monkeypatch, auth_router, "verify_password")
    body = {"username": "nobody", "password": PASSWORD, "device_name": "x"}
    t, out = run_in_thread(lambda: client.post("/api/v1/auth/login", json=body))
    assert parked.entered.wait(WAIT)
    try:
        assert_writes_proceed(bob)
    finally:
        parked.release.set()
        t.join(WAIT)
    assert out["r"].status_code == 401


def test_redeem_does_not_hold_the_write_lock_while_hashing(monkeypatch, factory, client, alice):
    parked = Parked(monkeypatch, auth_router, "hash_password")
    body = {
        "code": factory.invite(),
        "username": "newbie",
        "password": PASSWORD,
        "display_name": "N",
        "device_name": "d",
    }
    t, out = run_in_thread(lambda: client.post("/api/v1/auth/invite/redeem", json=body))
    assert parked.entered.wait(WAIT)
    try:
        assert_writes_proceed(alice)
    finally:
        parked.release.set()
        t.join(WAIT)
    assert out["r"].status_code == 201


def test_password_change_does_not_hold_the_write_lock_while_hashing(monkeypatch, client, alice, bob):
    parked = Parked(monkeypatch, users_router, "hash_password")
    t, out = run_in_thread(
        lambda: alice.put("/users/me/password", {"current_password": PASSWORD, "new_password": "another long password"})
    )
    assert parked.entered.wait(WAIT)
    try:
        assert_writes_proceed(bob)
    finally:
        parked.release.set()
        t.join(WAIT)
    assert out["r"].status_code == 204


def test_login_rechecks_the_account_after_hashing(monkeypatch, app, client, alice):
    """The lookup transaction is over when the hash runs, so the write transaction must re-verify the state."""
    original = auth_router.verify_password
    seen = {}

    def verify_then_change(settings, stored, password):
        ok = original(settings, stored, password)
        with app.state.session_factory() as db:  # would block for 10 s if login still held the write lock
            db.execute(update(User).where(User.username == "alice").values(**seen["change"]))
            db.commit()
        return ok

    monkeypatch.setattr(auth_router, "verify_password", verify_then_change)
    body = {"username": "alice", "password": PASSWORD, "device_name": "tablet"}
    for change in ({"disabled_at": 5}, {"password_hash": "x", "disabled_at": None}):
        seen["change"] = change
        r = client.post("/api/v1/auth/login", json=body)
        assert r.status_code == 401 and r.json()["code"] == "invalid_credentials"
    with app.state.session_factory() as db:
        assert len(db.scalars(select(Device).where(Device.user_id == alice.id)).all()) == 1  # no device was created


def test_password_change_rechecks_after_hashing(monkeypatch, app, client, alice):
    original = users_router.hash_password

    def hash_then_race(settings, password):
        out = original(settings, password)
        with app.state.session_factory() as db:
            db.execute(update(User).where(User.id == alice.id).values(password_hash="changed-meanwhile"))
            db.commit()
        return out

    monkeypatch.setattr(users_router, "hash_password", hash_then_race)
    r = alice.put("/users/me/password", {"current_password": PASSWORD, "new_password": "another long password"})
    assert r.status_code == 409
    with app.state.session_factory() as db:
        assert db.get(User, alice.id).password_hash == "changed-meanwhile"


def test_argon2_runs_at_most_two_at_a_time(monkeypatch):
    lock = threading.Lock()
    state = {"now": 0, "max": 0}

    class Slow:
        def _run(self):
            with lock:
                state["now"] += 1
                state["max"] = max(state["max"], state["now"])
            time.sleep(0.05)
            with lock:
                state["now"] -= 1

        def hash(self, password):
            self._run()
            return "hash"

        def verify(self, stored, password):
            self._run()
            return True

    monkeypatch.setattr(security, "hasher", lambda settings: Slow())
    settings = make_settings(__import__("pathlib").Path("."))
    threads = [
        threading.Thread(target=fn, args=(settings, *args))
        for fn, args in (
            [(security.hash_password, ("pw",))] * 3
            + [(security.verify_password, ("stored", "pw"))] * 3
            + [(security.verify_password, (None, "pw"))] * 2
        )
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(WAIT)
    assert state["max"] == security.ARGON2_CONCURRENCY == 2


def test_the_dummy_hash_for_unknown_users_is_computed_inside_a_slot(monkeypatch):
    """_dummy_hash used to hash outside any cap; it must not deadlock either when both slots are in use."""
    security._dummy_hash_cached.cache_clear()
    settings = make_settings(__import__("pathlib").Path("."))
    results = []
    ts = [
        threading.Thread(target=lambda: results.append(security.verify_password(settings, None, "x"))) for _ in range(5)
    ]
    for t in ts:
        t.start()
    for t in ts:
        t.join(WAIT)
    assert results == [False] * 5


def test_two_concurrent_redeems_of_one_code_create_one_account(monkeypatch, factory, app, client):
    code = factory.invite()
    barrier = threading.Barrier(2, timeout=WAIT)
    original = auth_router.hash_password

    def rendezvous(settings, password):
        barrier.wait()  # both requests have passed every pre-check before either may claim the invitation
        return original(settings, password)

    monkeypatch.setattr(auth_router, "hash_password", rendezvous)
    results = []

    def redeem(name):
        results.append(
            client.post(
                "/api/v1/auth/invite/redeem",
                json={"code": code, "username": name, "password": PASSWORD, "display_name": name, "device_name": "d"},
            )
        )

    ts = [threading.Thread(target=redeem, args=(n,)) for n in ("first", "second")]
    for t in ts:
        t.start()
    for t in ts:
        t.join(WAIT)
    assert sorted(r.status_code for r in results) == [201, 404], [r.text for r in results]
    with app.state.session_factory() as db:
        assert db.scalar(text("SELECT count(*) FROM users")) == 1
        inv = db.scalars(select(Invitation)).one()
        assert inv.used_at is not None and inv.used_by is not None


def test_two_concurrent_redeems_with_one_username_create_one_account(monkeypatch, factory, app, client):
    codes = [factory.invite(), factory.invite()]
    barrier = threading.Barrier(2, timeout=WAIT)
    original = auth_router.hash_password

    def rendezvous(settings, password):
        barrier.wait()
        return original(settings, password)

    monkeypatch.setattr(auth_router, "hash_password", rendezvous)
    results = []

    def redeem(code):
        results.append(
            client.post(
                "/api/v1/auth/invite/redeem",
                json={"code": code, "username": "same", "password": PASSWORD, "display_name": "S", "device_name": "d"},
            )
        )

    ts = [threading.Thread(target=redeem, args=(c,)) for c in codes]
    for t in ts:
        t.start()
    for t in ts:
        t.join(WAIT)
    assert sorted(r.status_code for r in results) == [201, 409]
    with app.state.session_factory() as db:
        # the loser's invitation is not burnt by its failed attempt
        assert len(db.scalars(select(Invitation).where(Invitation.used_at.is_(None))).all()) == 1


def test_concurrent_refresh_with_one_token_never_issues_two_sessions(client, alice):
    barrier = threading.Barrier(2, timeout=WAIT)
    results = []

    def refresh():
        barrier.wait()
        results.append(client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh}))

    ts = [threading.Thread(target=refresh) for _ in range(2)]
    for t in ts:
        t.start()
    for t in ts:
        t.join(WAIT)
    codes = sorted(r.status_code for r in results)
    assert codes == [200, 401]  # the second presentation is a replay of a rotated token
    winner = next(r for r in results if r.status_code == 200).json()
    # a replay is treated as theft: the session is gone, including the freshly issued token
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": winner["refresh_token"]}).status_code == 401


def test_message_seq_stays_unique_under_concurrent_senders(factory, alice, bob):
    cid = factory.direct(alice, bob)
    seqs = []

    def worker():
        for _ in range(5):
            seqs.append(alice.send(cid, "x").json()["message"]["seq"])

    ts = [threading.Thread(target=worker) for _ in range(4)]
    for t in ts:
        t.start()
    for t in ts:
        t.join(WAIT * 4)
    assert len(seqs) == 20 and len(set(seqs)) == 20

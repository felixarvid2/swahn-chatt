"""Per-username login lockouts survive a restart (failures are persisted, not every rate-limit hit)."""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select, update

from app import clock
from app.models import LoginFailure
from app.services import lockout
from tests.conftest import PASSWORD, Factory

LOGIN = "/api/v1/auth/login"
PROXY = "172.16.0.0/12"


def attempt(client, username="alice", password="wrong-password-xyz", ip="203.0.113.1"):
    return client.post(
        LOGIN,
        json={"username": username, "password": password, "device_name": "x"},
        headers={"X-Forwarded-For": ip},
    )


@pytest.fixture
def locked_out(make_app):
    """An app whose user 'alice' has been locked out by ten failures from ten different addresses."""
    app, client = make_app(rate_limit_enabled=True, trusted_proxies=PROXY)
    proxy = TestClient(app, client=("172.16.0.9", 4000))
    Factory(app, client).user("alice")
    for i in range(10):
        assert attempt(proxy, ip=f"203.0.113.{i + 1}").status_code == 401
    assert attempt(proxy, ip="198.51.100.1").status_code == 429
    return app


def restart(make_app, app):
    new_app, client = make_app(
        rate_limit_enabled=True, trusted_proxies=PROXY, database_url=app.state.settings.database_url
    )
    return new_app, TestClient(new_app, client=("172.16.0.9", 4000))


def test_lockout_survives_an_app_restart(make_app, locked_out):
    _app2, proxy2 = restart(make_app, locked_out)
    r = attempt(proxy2, password=PASSWORD, ip="198.51.100.50")  # right password, new address, fresh process
    assert r.status_code == 429 and r.json()["code"] == "rate_limited"
    assert int(r.headers["Retry-After"]) >= 1
    assert attempt(proxy2, "bob", ip="198.51.100.51").status_code == 401  # other names are untouched


def test_unknown_usernames_are_locked_after_a_restart_too(make_app):
    app, client = make_app(rate_limit_enabled=True, trusted_proxies=PROXY)
    proxy = TestClient(app, client=("172.16.0.9", 4000))
    for i in range(10):
        assert attempt(proxy, "ghost", ip=f"203.0.113.{i + 1}").status_code == 401
    _app2, proxy2 = restart(make_app, app)
    assert attempt(proxy2, "ghost", ip="198.51.100.9").status_code == 429


def test_failures_older_than_the_window_are_not_restored(make_app, locked_out):
    day = 86_400_000
    with locked_out.state.session_factory() as db:
        db.execute(update(LoginFailure).values(at=clock.now_ms() - day - 1000))
        db.commit()
    _app2, proxy2 = restart(make_app, locked_out)
    assert attempt(proxy2, password=PASSWORD, ip="198.51.100.60").status_code == 200


def test_hourly_lock_decays_but_the_daily_count_still_applies_after_a_restart(make_app, locked_out):
    hour = 3_600_000
    with locked_out.state.session_factory() as db:
        db.execute(update(LoginFailure).values(at=clock.now_ms() - hour - 1000))
        db.commit()
    _app2, proxy2 = restart(make_app, locked_out)
    # 10 failures, all more than an hour old: below the daily limit of 30, so the right password works
    assert attempt(proxy2, password=PASSWORD, ip="198.51.100.61").status_code == 200


def test_only_failures_are_written_and_never_the_username(make_app):
    app, client = make_app(rate_limit_enabled=True)
    Factory(app, client).user("alice")
    for _ in range(2):  # (the per-address limit of 5 logins per minute stays below)
        assert attempt(client, ip="203.0.113.5").status_code == 401
    for _ in range(2):
        assert attempt(client, password=PASSWORD, ip="203.0.113.5").status_code == 200
    with app.state.session_factory() as db:
        rows = db.execute(select(LoginFailure.key_hash, LoginFailure.at)).all()
    assert len(rows) == 2  # two failures; successful logins and other requests wrote nothing
    expected = lockout.key_hash(app.state.settings, "alice")
    assert {r.key_hash for r in rows} == {expected} and "alice" not in expected


def test_nothing_is_persisted_when_rate_limiting_is_off(app, client):
    Factory(app, client).user("alice")
    for _ in range(12):
        assert attempt(client, ip="203.0.113.5").status_code == 401  # no lockout without the limiter
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(LoginFailure)) == 0


def test_maintenance_prunes_old_failures(locked_out):
    from app.services.maintenance import prune

    with locked_out.state.session_factory() as db:
        db.execute(update(LoginFailure).values(at=clock.now_ms() - 2 * 86_400_000))
        db.commit()
        assert prune(db, locked_out.state.settings)["login_failures"] == 10
        assert db.scalar(select(func.count()).select_from(LoginFailure)) == 0

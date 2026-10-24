"""Operator alerts through ntfy: what triggers them, what they say (nothing sensitive) and how they are rate limited."""

import asyncio
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import ConfigError
from app.main import _maintenance_loop, create_app
from app.services.alerts import ALERTS, MAX_PER_HOUR, AdminAlerts
from tests.conftest import PASSWORD, Factory, make_settings

TOPIC = "https://ntfy.example.org/fc-admin-SECRETTOPIC"
TOKEN = "tk_secrettokenvalue"
PROXY = "172.16.0.0/12"


@pytest.fixture
def alerting(tmp_path, new_settings):
    """build(**overrides) -> (app, client, sent) with alerts on and a recording ntfy transport."""
    apps = []

    def build(status=200, **overrides):
        sent: list[httpx.Request] = []

        def handler(request):
            sent.append(request)
            if status == "error":
                raise httpx.ConnectError("down", request=request)
            return httpx.Response(status)

        overrides.setdefault("admin_ntfy_url", TOPIC)
        overrides.setdefault("admin_ntfy_token", TOKEN)
        app = create_app(
            new_settings(tmp_path / f"d{len(apps)}", **overrides), alert_transport=httpx.MockTransport(handler)
        )
        client = TestClient(app, base_url="http://testserver")
        client.__enter__()
        apps.append(client)
        return app, client, sent

    yield build
    for c in apps:
        c.__exit__(None, None, None)


def texts(sent):
    return [r.content.decode() for r in sent]


def lock_out(app, name="alice"):
    proxy = TestClient(app, client=("172.16.0.9", 4000))
    for i in range(10):
        r = proxy.post(
            "/api/v1/auth/login",
            json={"username": name, "password": "wrong-password-xyz", "device_name": "x"},
            headers={"X-Forwarded-For": f"203.0.113.{i + 1}"},
        )
        assert r.status_code == 401


# ---- configuration


def test_url_must_be_https_outside_development(tmp_path):
    with pytest.raises(ConfigError) as exc:
        make_settings(tmp_path, env="production", admin_ntfy_url="http://ntfy.example.org/topicSECRET")
    assert "SECRET" not in str(exc.value)
    for bad in ("not a url", "ftp://ntfy.example.org/t", "https://"):
        with pytest.raises(ConfigError):
            make_settings(tmp_path, admin_ntfy_url=bad)
    assert make_settings(tmp_path, env="development", admin_ntfy_url="http://ntfy.local/t").admin_ntfy_url
    assert make_settings(tmp_path, env="production", admin_ntfy_url=TOPIC).admin_ntfy_url == TOPIC


def test_alerts_are_off_without_a_url(make_app):
    app, client = make_app()
    assert app.state.alerts.enabled is False
    assert app.state.alerts.send("login_lockout") is False


# ---- triggers


def test_login_lockout_alert_says_nothing_about_who(alerting):
    app, _client, sent = alerting(rate_limit_enabled=True, trusted_proxies=PROXY)
    Factory(app, _client).user("alice")
    lock_out(app, "alice")
    app.state.alerts.flush()
    assert texts(sent) == ["A login lockout was triggered."]
    req = sent[0]
    assert req.headers["authorization"] == f"Bearer {TOKEN}"
    assert req.headers["content-type"].startswith("text/plain")
    blob = (str(req.url.path) + req.content.decode() + str(dict(req.headers))).lower()
    assert "alice" not in blob.replace("authorization", "")
    assert "203.0.113" not in blob
    # more failures while locked do not alert again
    lock_out_again = TestClient(app, client=("172.16.0.9", 4000)).post(
        "/api/v1/auth/login", json={"username": "alice", "password": PASSWORD, "device_name": "x"}
    )
    assert lock_out_again.status_code == 429
    app.state.alerts.flush()
    assert len(sent) == 1


def test_no_alert_for_a_few_failed_logins(alerting):
    app, client, sent = alerting(rate_limit_enabled=True, trusted_proxies=PROXY)
    Factory(app, client).user("alice")
    proxy = TestClient(app, client=("172.16.0.9", 4000))
    for i in range(3):
        proxy.post(
            "/api/v1/auth/login",
            json={"username": "alice", "password": "nope-nope-nope", "device_name": "x"},
            headers={"X-Forwarded-For": f"203.0.113.{i + 1}"},
        )
    app.state.alerts.flush()
    assert sent == []


def test_refresh_token_reuse_alert(alerting):
    app, client, sent = alerting()
    alice = Factory(app, client).user("alice")
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh}).status_code == 200
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": alice.refresh})
    assert r.json()["code"] == "token_reused"
    app.state.alerts.flush()
    assert texts(sent) == ["A refresh token was reused and its session was revoked."]
    assert "alice" not in sent[0].content.decode() and alice.refresh not in sent[0].content.decode()


def test_new_admin_alert_for_invitations_and_promotions(alerting):
    app, client, sent = alerting()
    f = Factory(app, client)
    boss = f.user("boss", admin=True)  # redeemed an administrator invitation
    app.state.alerts.flush()
    assert texts(sent) == ["A new administrator was granted."]
    member = f.user("member")
    app.state.alerts._last.clear()  # the one-minute debounce would hide the promotion below
    sent.clear()
    assert boss.patch(f"/admin/users/{member.id}", {"is_admin": True}).status_code == 200
    app.state.alerts.flush()
    assert texts(sent) == ["A new administrator was granted."]
    app.state.alerts._last.clear()
    sent.clear()
    assert boss.patch(f"/admin/users/{member.id}", {"is_admin": False}).status_code == 200  # demotion: quiet
    assert boss.patch(f"/admin/users/{member.id}", {"is_admin": False}).status_code == 200  # no change: quiet
    app.state.alerts.flush()
    assert sent == []


def test_low_disk_alert_from_readyz_is_debounced_to_six_hours(alerting, monkeypatch):
    app, client, sent = alerting()
    monkeypatch.setattr("app.routers.health.free_disk_bytes", lambda _p: 0)
    now = [1000.0]
    app.state.alerts._clock = lambda: now[0]
    for _ in range(3):
        assert client.get("/readyz").status_code == 503
    app.state.alerts.flush()
    assert texts(sent) == [ALERTS["low_disk"][0]]
    now[0] += 5 * 3600
    client.get("/readyz")
    app.state.alerts.flush()
    assert len(sent) == 1
    now[0] += 3600 + 1
    client.get("/readyz")
    app.state.alerts.flush()
    assert len(sent) == 2


def test_healthy_disk_sends_nothing(alerting):
    app, client, sent = alerting()
    assert client.get("/readyz").status_code == 200
    app.state.alerts.flush()
    assert sent == []


def run_loop_once(app):
    async def go():
        stop = asyncio.Event()
        task = asyncio.create_task(_maintenance_loop(app, stop))
        await asyncio.sleep(1.5)
        stop.set()
        await task

    asyncio.run(go())


def test_maintenance_failure_alert(alerting, monkeypatch):
    app, _client, sent = alerting()

    def boom(*_a, **_k):
        raise RuntimeError("cannot prune")

    monkeypatch.setattr("app.main.prune", boom)
    run_loop_once(app)
    app.state.alerts.flush()
    assert texts(sent) == [ALERTS["maintenance_failed"][0]]


def test_database_maintenance_failure_alert(alerting, monkeypatch):
    app, _client, sent = alerting()
    monkeypatch.setattr("app.services.dbmaint._sqlite", lambda *_a, **_k: (_ for _ in ()).throw(OSError("x")))
    monkeypatch.setattr("app.services.dbmaint._postgres", lambda *_a, **_k: (_ for _ in ()).throw(OSError("x")))
    run_loop_once(app)
    app.state.alerts.flush()
    assert texts(sent) == [ALERTS["maintenance_failed"][0]]


def test_maintenance_loop_alerts_on_low_disk_and_is_quiet_otherwise(alerting, monkeypatch):
    app, _client, sent = alerting()
    run_loop_once(app)
    app.state.alerts.flush()
    assert sent == []
    monkeypatch.setattr("app.main.free_disk_bytes", lambda _p: 0)
    run_loop_once(app)
    app.state.alerts.flush()
    assert texts(sent) == [ALERTS["low_disk"][0]]


# ---- delivery and rate limiting


def test_a_dead_or_rejecting_ntfy_server_never_affects_requests(alerting, caplog):
    for status in ("error", 500):
        app, client, sent = alerting(status=status)
        caplog.set_level(logging.WARNING)
        a = Factory(app, client).user("alice")
        client.post("/api/v1/auth/refresh", json={"refresh_token": a.refresh})
        r = client.post("/api/v1/auth/refresh", json={"refresh_token": a.refresh})
        assert r.status_code == 401
        app.state.alerts.flush()
        assert len(sent) == 1 and app.state.alerts.sent == 0
    assert "SECRETTOPIC" not in caplog.text


def test_each_kind_has_a_minimum_interval_and_there_is_an_hourly_cap():
    now = [0.0]
    alerts = AdminAlerts(TOPIC, transport=httpx.MockTransport(lambda r: httpx.Response(200)), clock=lambda: now[0])
    assert alerts.send("login_lockout") is True
    assert alerts.send("login_lockout") is False  # within 30 minutes
    assert alerts.send("token_reuse") is True  # another kind
    now[0] += 31 * 60
    assert alerts.send("login_lockout") is True
    assert alerts.send("nonsense") is False
    # admin_granted may repeat every minute, but never more than MAX_PER_HOUR alerts in all
    now[0] = 10_000.0
    accepted = 0
    for _ in range(MAX_PER_HOUR + 10):
        now[0] += 61
        accepted += alerts.send("admin_granted")
        if now[0] > 10_000.0 + 3500:
            break
    assert 0 < accepted <= MAX_PER_HOUR
    now[0] += 3601
    assert alerts.send("admin_granted") is True
    alerts.stop()


def test_messages_are_fixed_texts_without_anything_sensitive():
    for kind, (text, interval) in ALERTS.items():
        assert interval > 0 and text.isascii() and len(text) < 100, kind
        assert "http" not in text and "@" not in text

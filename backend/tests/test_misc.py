import logging
import time

import pytest
from fastapi.testclient import TestClient

from app import clock
from app.config import ConfigError, Settings
from app.main import create_app
from app.migrate import code_head
from app.models import Device, Invitation
from app.models import Session as AuthSession
from app.services.maintenance import prune
from tests.conftest import SECRET, Factory, make_settings


def test_health_and_ready(client):
    assert client.get("/healthz").json() == {"status": "ok"}
    ready = client.get("/readyz").json()
    assert ready["status"] == "ready" and ready["migration"] == code_head()
    assert client.get("/api/v1/healthz").status_code == 200
    assert client.get("/api/v1/readyz").status_code == 200


def test_readyz_503_when_database_is_down(app, client):
    class Broken:
        def __enter__(self):
            raise RuntimeError("db down")

        def __exit__(self, *a):
            return False

    good = app.state.session_factory
    app.state.session_factory = lambda: Broken()
    try:
        assert client.get("/readyz").status_code == 503
        assert client.get("/healthz").status_code == 200  # liveness is independent of the DB
    finally:
        app.state.session_factory = good


def test_error_bodies_have_code_and_message(client, alice):
    for r in (
        client.get("/api/v1/nope"),
        client.get("/api/v1/users/me"),
        alice.put("/users/me/password", {"current_password": "x"}),
        client.request("DELETE", "/api/v1/auth/login"),
        alice.get("/sync?limit=0"),
    ):
        body = r.json()
        assert set(body) >= {"code", "message"} and isinstance(body["message"], str), body
    assert client.get("/api/v1/nope").json()["code"] == "not_found"
    assert client.request("DELETE", "/api/v1/auth/login").json()["code"] == "method_not_allowed"
    r = alice.put("/users/me/password", {"current_password": "x", "new_password": "short"})
    assert r.status_code == 422 and r.json()["code"] == "invalid_request"
    assert "short" not in r.text  # validation errors never echo the submitted input


def test_production_requires_a_strong_secret(tmp_path):
    with pytest.raises(ConfigError, match="SECRET_KEY"):
        Settings(env="production", data_dir=tmp_path)
    with pytest.raises(ConfigError):
        Settings(env="production", data_dir=tmp_path, secret_key="too-short")
    with pytest.raises(ConfigError):
        create_app(Settings(env="production", data_dir=tmp_path, secret_key="x" * 31))
    ok = Settings(env="production", data_dir=tmp_path, secret_key="x" * 32)
    assert ok.secret_key == "x" * 32 and ok.env == "production"
    assert Settings(data_dir=tmp_path, secret_key="y" * 40).env == "production"  # production is the default


def test_development_generates_an_ephemeral_secret_with_a_warning(tmp_path, caplog):
    caplog.set_level(logging.WARNING)
    a = Settings(env="development", data_dir=tmp_path)
    b = Settings(env="development", data_dir=tmp_path)
    assert len(a.secret_key) >= 32 and a.secret_key != b.secret_key
    assert any("ephemeral" in r.getMessage() for r in caplog.records)


def test_settings_defaults_and_env(tmp_path, monkeypatch):
    s = Settings(env="development", data_dir=tmp_path, secret_key=SECRET)
    assert s.database_url == f"sqlite:///{(tmp_path / 'familychat.db').resolve()}"
    assert s.attachments_dir == tmp_path / "attachments"
    assert (s.max_attachment_mb, s.user_quota_mb, s.access_token_minutes, s.refresh_token_days) == (100, 2048, 15, 60)
    assert s.push_allow_http is False and s.expose_docs is False and s.trusted_networks == []
    monkeypatch.setenv("FAMILYCHAT_ENV", "development")
    monkeypatch.setenv("FAMILYCHAT_DATA_DIR", str(tmp_path / "d"))
    monkeypatch.setenv("FAMILYCHAT_MAX_ATTACHMENT_MB", "7")
    monkeypatch.setenv("FAMILYCHAT_TRUSTED_PROXIES", "10.0.0.0/8, 172.16.0.0/12")
    monkeypatch.setenv("FAMILYCHAT_ATTACHMENTS_DIR", str(tmp_path / "att"))
    e = Settings()
    assert e.max_attachment_mb == 7 and len(e.trusted_networks) == 2 and e.attachments_dir == tmp_path / "att"
    assert e.sqlite_path == (tmp_path / "d" / "familychat.db").resolve()


def test_docs_exposure(tmp_path):
    prod = create_app(make_settings(tmp_path / "p", env="production"))
    with TestClient(prod) as c:
        assert c.get("/api/v1/docs").status_code == 404
        assert c.get("/api/v1/openapi.json").status_code == 404
    exposed = create_app(make_settings(tmp_path / "e", env="production", expose_docs=True))
    with TestClient(exposed) as c:
        assert c.get("/api/v1/docs").status_code == 200
        assert c.get("/api/v1/openapi.json").status_code == 200
    dev = create_app(make_settings(tmp_path / "d"))
    with TestClient(dev) as c:
        spec = c.get("/api/v1/openapi.json").json()
        assert "/api/v1/messages" in spec["paths"] and "/api/v1/sync/ack" in spec["paths"]


def test_every_documented_route_exists(client):
    spec = client.get("/api/v1/openapi.json").json()["paths"]
    documented = {
        ("post", "/auth/invite/redeem"), ("post", "/auth/login"), ("post", "/auth/refresh"), ("post", "/auth/logout"),
        ("post", "/admin/invitations"), ("get", "/admin/invitations"), ("delete", "/admin/invitations/{invitation_id}"),
        ("delete", "/admin/users/{user_id}"), ("get", "/users"), ("get", "/users/me"), ("patch", "/users/me"),
        ("put", "/users/me/password"), ("get", "/users/{user_id}/devices"), ("get", "/devices/me"),
        ("put", "/devices/me/push"), ("delete", "/devices/me/push"), ("delete", "/devices/{device_id}"),
        ("put", "/keys"), ("get", "/keys/status"), ("get", "/keys/{user_id}"), ("get", "/conversations"),
        ("post", "/conversations"), ("get", "/conversations/{conversation_id}"),
        ("patch", "/conversations/{conversation_id}"), ("post", "/conversations/{conversation_id}/members"),
        ("patch", "/conversations/{conversation_id}/members/{user_id}"),
        ("delete", "/conversations/{conversation_id}/members/{user_id}"),
        ("get", "/conversations/{conversation_id}/messages"), ("post", "/conversations/{conversation_id}/read"),
        ("post", "/messages"), ("get", "/sync"), ("post", "/sync/ack"), ("post", "/attachments"),
        ("get", "/attachments/{attachment_id}"),
    }  # fmt: skip
    missing = [(m, p) for m, p in documented if m not in spec.get("/api/v1" + p, {})]
    assert missing == []


def test_prune_removes_expired_sessions_and_invitations(factory, app, alice):
    now = clock.now_ms()
    day = 86_400_000
    expired_code = factory.invite()
    live_code = factory.invite()
    with app.state.session_factory() as db:
        invs = db.query(Invitation).filter(Invitation.used_at.is_(None)).all()
        assert len(invs) == 2
        invs[0].expires_at = now - 8 * day
        keep_id = invs[1].id
        s = AuthSession(
            id="old-session", user_id=alice.id, device_id=alice.device_id, refresh_token_hash="h" * 64,
            created_at=now - 90 * day, expires_at=now - 30 * day,
        )  # fmt: skip
        db.add(s)
        db.commit()
        result = prune(db, app.state.settings)
        assert result["sessions"] == 1 and result["invitations"] == 1
        assert db.get(AuthSession, "old-session") is None
        assert db.query(AuthSession).count() == 1  # alice's live session stays
        assert db.get(Invitation, keep_id) is not None
        assert db.get(Device, alice.device_id).revoked_at is None
    assert expired_code != live_code


def test_background_tasks_run_in_lifespan(tmp_path, template_db):
    import shutil

    shutil.copy(template_db, tmp_path / "familychat.db")
    app = create_app(make_settings(tmp_path, background_tasks=True))
    with TestClient(app) as client:
        f = Factory(app, client)
        a = f.user("alice")
        assert a.get("/users/me").status_code == 200
        time.sleep(0.3)  # the maintenance loop's first pass ran at startup without crashing the app
        assert client.get("/healthz").status_code == 200


def test_security_headers_and_no_store(client, alice):
    r = alice.get("/users/me")
    assert r.headers["x-content-type-options"] == "nosniff" and r.headers["cache-control"] == "no-store"


def test_device_numbers_are_recycled_after_127(factory, alice, app):
    from sqlalchemy import update

    with app.state.session_factory() as db:
        for n in range(2, 128):
            db.add(Device(
                id=f"00000000-0000-4000-8000-{n:012d}", user_id=alice.id, number=n, name="old",
                created_at=1, last_seen_at=1, revoked_at=2, acked_cursor=0,
            ))  # fmt: skip
        db.commit()
    again = factory.login(alice, "one more", keys=False)
    assert again.device["number"] == 2  # the oldest revoked device gave up its number
    with app.state.session_factory() as db:
        nums = [n for (n,) in db.query(Device.number).filter(Device.user_id == alice.id)]
        assert len(nums) == len(set(nums))
        db.execute(update(Device).where(Device.id == again.device_id).values(name="x"))
        db.commit()

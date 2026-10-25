"""Push retries with backoff, per-device push health, and the push test endpoint (mocked transport)."""

import time

import httpx
from fastapi.testclient import TestClient

from app.main import create_app
from app.models import Device
from tests.conftest import Factory

ENDPOINT = "https://ntfy.example.org/upABCDEF123?up=1"
OTHER = "https://ntfy.example.org/upZZZZZZZ999?up=1"
FAST = (0.02, 0.02, 0.02)


def build(tmp_path, new_settings, handler, delays=FAST, **overrides):
    app = create_app(new_settings(tmp_path, **overrides), push_transport=httpx.MockTransport(handler))
    app.state.push._retry_delays = delays
    return app


def register(actor, endpoint=ENDPOINT):
    assert actor.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": endpoint}).status_code == 204


def device_row(app, device_id):
    with app.state.session_factory() as db:
        d = db.get(Device, device_id)
        return d.last_push_status, d.push_failures, d.last_push_at, d.push_endpoint


def test_transient_status_is_retried_until_it_succeeds(tmp_path, new_settings):
    statuses = [503, 429, 201]
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(statuses[min(len(calls), 3) - 1])

    app = build(tmp_path, new_settings, handler)
    with TestClient(app) as client:
        f = Factory(app, client)
        bob = f.user("bob")
        register(bob)
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert len(calls) == 3
        status, failures, at, endpoint = device_row(app, bob.device_id)
        assert (status, failures, endpoint) == (201, 0, ENDPOINT) and at
        assert app.state.push.sent == 1


def test_network_errors_are_retried_then_given_up_and_counted(tmp_path, new_settings):
    calls = []

    def handler(request):
        calls.append(request)
        raise httpx.ConnectError("down", request=request)

    app = build(tmp_path, new_settings, handler)
    with TestClient(app) as client:
        bob = Factory(app, client).user("bob")
        register(bob)
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert len(calls) == 4  # the first attempt and three retries
        assert device_row(app, bob.device_id)[:2] == (0, 1)  # status 0 = no answer; one push failed for good
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert device_row(app, bob.device_id)[1] == 2  # consecutive
        assert bob.get("/devices/me").json()["last_push_status"] == 0


def test_a_success_resets_the_failure_count(tmp_path, new_settings):
    fail = [True]

    def handler(request):
        return httpx.Response(500 if fail[0] else 201)

    app = build(tmp_path, new_settings, handler, delays=())
    with TestClient(app) as client:
        bob = Factory(app, client).user("bob")
        register(bob)
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert device_row(app, bob.device_id)[:2] == (500, 1)
        fail[0] = False
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert device_row(app, bob.device_id)[:2] == (201, 0)


def test_client_errors_are_not_retried_and_gone_endpoints_are_cleared(tmp_path, new_settings):
    calls = []
    code = [400]

    def handler(request):
        calls.append(request)
        return httpx.Response(code[0])

    app = build(tmp_path, new_settings, handler)
    with TestClient(app) as client:
        bob = Factory(app, client).user("bob")
        register(bob)
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert len(calls) == 1
        assert device_row(app, bob.device_id)[:2] == (400, 1)
        code[0] = 410
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        assert len(calls) == 2  # 404/410: no retry
        status, failures, _at, endpoint = device_row(app, bob.device_id)
        assert (status, failures, endpoint) == (410, 0, None)


def test_a_waiting_retry_does_not_block_other_devices(tmp_path, new_settings):
    seen = []

    def handler(request):
        seen.append((str(request.url), time.monotonic()))
        return httpx.Response(503 if str(request.url) == ENDPOINT else 201)

    app = build(tmp_path, new_settings, handler, delays=(0.6, 0.6, 0.6))
    with TestClient(app) as client:
        f = Factory(app, client)
        bob, carol = f.user("bob"), f.user("carol")
        register(bob)
        register(carol, OTHER)
        start = time.monotonic()
        app.state.push.notify([bob.device_id])
        time.sleep(0.2)  # bob's first attempt failed; the retry is scheduled 0.6 s out
        app.state.push.notify([carol.device_id])
        deadline = time.monotonic() + 0.5
        while not any(u == OTHER for u, _ in seen) and time.monotonic() < deadline:
            time.sleep(0.02)
        carol_at = next(t for u, t in seen if u == OTHER)
        assert carol_at - start < 0.55  # sent while bob's retry was still waiting
        assert sum(1 for u, _ in seen if u == ENDPOINT) == 1
        app.state.push.flush(timeout=15)
        assert sum(1 for u, _ in seen if u == ENDPOINT) == 4


def test_a_fresh_push_supersedes_the_scheduled_retry(tmp_path, new_settings):
    statuses = []

    def handler(request):
        statuses.append(1)
        return httpx.Response(503 if len(statuses) == 1 else 201)

    app = build(tmp_path, new_settings, handler, delays=(30.0,))
    with TestClient(app) as client:
        bob = Factory(app, client).user("bob")
        register(bob)
        app.state.push.notify([bob.device_id])
        deadline = time.monotonic() + 3
        while len(statuses) < 1 and time.monotonic() < deadline:
            time.sleep(0.01)
        time.sleep(0.1)
        app.state.push.notify([bob.device_id])  # replaces the retry that would wait 30 s
        app.state.push.flush(timeout=5)
        assert len(statuses) == 2
        assert device_row(app, bob.device_id)[:2] == (201, 0)


def test_health_is_exposed_to_admins_but_not_to_other_users(tmp_path, new_settings):
    app = build(tmp_path, new_settings, lambda r: httpx.Response(201))
    with TestClient(app) as client:
        f = Factory(app, client)
        admin, bob = f.user("admin", admin=True), f.user("bob")
        before = [u for u in admin.get("/admin/users").json() if u["id"] == bob.id][0]["devices"]
        assert before[0]["push_registered"] is False and before[0]["last_push_at"] is None
        register(bob)
        app.state.push.notify([bob.device_id])
        app.state.push.flush()
        (dev,) = [u for u in admin.get("/admin/users").json() if u["id"] == bob.id][0]["devices"]
        assert dev["id"] == bob.device_id and dev["push_registered"] is True
        assert dev["last_push_status"] == 201 and dev["push_failures"] == 0 and dev["last_push_at"]
        assert "endpoint" not in str(dev)
        # contacts see the plain device object
        seen = admin.get(f"/users/{bob.id}/devices")
        assert seen.status_code == 200
        assert all("last_push_at" not in d and "last_push_status" not in d for d in seen.json())


# ---- POST /devices/me/push/test


def test_push_test_needs_a_registered_endpoint(factory, app, bob):
    r = bob.post("/devices/me/push/test")
    assert r.status_code == 409 and r.json()["code"] == "push_not_configured"
    assert app.state.pushes == []


def test_push_test_in_production_without_allow_list_is_409(make_app):
    app, client = make_app(env="production", push_allowed_hosts="")
    bob = Factory(app, client).user("bob")
    r = bob.post("/devices/me/push/test")
    assert r.status_code == 409 and r.json()["code"] == "push_not_configured"


def test_push_test_sends_one_content_free_push_and_shows_the_result(factory, app, bob):
    assert bob.get("/devices/me").json()["last_push_at"] is None
    register(bob)
    app.state.push.flush()
    n = len(app.state.pushes)
    r = bob.post("/devices/me/push/test")
    assert r.status_code == 202 and r.json() == {"status": "queued"}
    app.state.push.flush()
    assert len(app.state.pushes) == n + 1
    req = app.state.pushes[-1]
    assert str(req.url) == ENDPOINT and req.content == b'{"t":"sync"}'
    me = bob.get("/devices/me").json()
    assert me["last_push_status"] == 201 and me["last_push_at"] > 0
    assert me["id"] == bob.device_id  # still the normal device object


def test_push_test_is_rate_limited_per_device(make_app):
    app, client = make_app(rate_limit_enabled=True)
    f = Factory(app, client)
    bob, carol = f.user("bob"), f.user("carol")
    register(bob)
    register(carol, OTHER)
    for _ in range(3):
        assert bob.post("/devices/me/push/test").status_code == 202
    r = bob.post("/devices/me/push/test")
    assert r.status_code == 429 and "retry-after" in r.headers
    assert carol.post("/devices/me/push/test").status_code == 202  # another device has its own budget


def test_push_test_requires_authentication(client):
    assert client.post("/api/v1/devices/me/push/test").status_code == 401

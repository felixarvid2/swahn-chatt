import json
import time

import httpx

from app.main import create_app
from tests.conftest import Factory, make_settings

ENDPOINT = "https://ntfy.example.org/upABCDEF123?up=1"


def test_push_endpoint_registration_rules(make_app):
    app, client = make_app()
    a = Factory(app, client).user("alice")
    assert a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "http://ntfy.local/up1"}).status_code == 422
    assert a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "ftp://x/y"}).status_code == 422
    assert a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "https://"}).status_code == 422
    assert a.put("/devices/me/push", {"kind": "fcm", "endpoint": ENDPOINT}).status_code == 422
    assert a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT}).status_code == 204
    assert a.delete("/devices/me/push").status_code == 204

    app2, client2 = make_app(push_allow_http=True)
    b = Factory(app2, client2).user("bob")
    assert (
        b.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "http://192.168.1.5:2586/up1"}).status_code == 204
    )


def test_push_is_content_free_and_only_for_offline_devices(factory, app, client, alice, bob):
    cid = factory.direct(alice, bob)
    app.state.push.flush()  # let hints for the setup traffic drain before bob registers an endpoint
    assert bob.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT}).status_code == 204
    secret = "my very secret text 12345"
    r = alice.send(cid, secret)
    assert r.status_code == 201
    app.state.push.flush()
    assert len(app.state.pushes) == 1
    req = app.state.pushes[0]
    assert str(req.url) == ENDPOINT and req.method == "POST"
    assert json.loads(req.content) == {"t": "sync"}
    blob = (str(req.url) + req.content.decode() + str(dict(req.headers))).lower()
    for forbidden in (secret.lower(), alice.username, alice.id, cid, r.json()["message"]["id"], "ct|"):
        assert forbidden not in blob
    # a device that holds a WebSocket gets the hint there instead of a push
    with client.websocket_connect("/api/v1/ws", headers=bob.headers) as ws:
        ws.receive_json()
        alice.send(cid, "second")
        assert ws.receive_json()["type"] == "sync"
        app.state.push.flush()
    assert len(app.state.pushes) == 1
    # alice has no endpoint and is never pushed
    bob.send(cid, "reply")
    app.state.push.flush()
    assert len(app.state.pushes) == 1


def test_push_for_other_events_and_cleared_registration(factory, app, alice, bob):
    assert alice.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT}).status_code == 204
    factory.direct(bob, alice)  # conversation_updated -> hint -> push
    app.state.push.flush()
    assert len(app.state.pushes) >= 1
    n = len(app.state.pushes)
    assert alice.delete("/devices/me/push").status_code == 204
    factory.group(bob, [alice])
    app.state.push.flush()
    assert len(app.state.pushes) == n


def test_gone_endpoint_is_forgotten(tmp_path, template_db):
    import shutil

    shutil.copy(template_db, tmp_path / "familychat.db")
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(410)

    app = create_app(make_settings(tmp_path), push_transport=httpx.MockTransport(handler))
    from fastapi.testclient import TestClient

    with TestClient(app) as client:
        f = Factory(app, client)
        a, b = f.user("alice"), f.user("bob")
        b.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT})
        cid = f.direct(a, b)
        a.send(cid, "x")
        app.state.push.flush()
        assert len(calls) >= 1
        n = len(calls)
        a.send(cid, "y")
        app.state.push.flush()
        assert len(calls) == n  # endpoint was dropped after the 410


def test_push_never_blocks_requests_and_survives_failures(tmp_path, template_db):
    import shutil

    shutil.copy(template_db, tmp_path / "familychat.db")
    calls = []

    def slow_failing(request):
        calls.append(request)
        time.sleep(1.5)
        raise httpx.ConnectTimeout("simulated timeout", request=request)

    app = create_app(make_settings(tmp_path), push_transport=httpx.MockTransport(slow_failing))
    from fastapi.testclient import TestClient

    with TestClient(app) as client:
        f = Factory(app, client)
        a, b = f.user("alice"), f.user("bob")
        b.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT})
        cid = f.direct(a, b)
        start = time.monotonic()
        assert a.send(cid, "x").status_code == 201
        assert time.monotonic() - start < 1.0  # the push runs on its own thread
        app.state.push.flush(timeout=10)
        assert len(calls) >= 1
        assert a.send(cid, "still works").status_code == 201


def test_push_client_has_five_second_timeout(app):
    assert app.state.push._client.timeout == httpx.Timeout(5.0)
    assert app.state.push._client.follow_redirects is False


def test_http_endpoint_skipped_in_production_mode_even_if_stored(make_app):
    app, client = make_app()
    f = Factory(app, client)
    a = f.user("alice")
    from app.models import Device

    with app.state.session_factory() as db:
        d = db.get(Device, a.device_id)
        d.push_kind, d.push_endpoint = "unifiedpush", "http://evil.local/x"
        db.commit()
    b = f.user("bob")
    f.direct(b, a)
    app.state.push.flush()
    assert app.state.pushes == []


def test_push_allowed_hosts_restricts_endpoints(make_app):
    app, client = make_app(push_allowed_hosts="ntfy.example.org, push.family.ts.net")
    a = Factory(app, client).user("alice")
    assert a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": ENDPOINT}).status_code == 204
    assert (
        a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "https://PUSH.family.ts.net/up"}).status_code
        == 204
    )
    assert (
        a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "https://169.254.169.254/x"}).status_code == 422
    )
    assert (
        a.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "https://evil.example.com/up"}).status_code == 422
    )

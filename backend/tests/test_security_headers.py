"""Security headers on every response, a relaxed CSP only for the Swagger UI page, HSTS on request."""

import re

BASE = {
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "x-frame-options": "DENY",
    "cross-origin-resource-policy": "same-site",
    "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
}


def assert_base(r, csp=True):
    for name, value in BASE.items():
        if name == "content-security-policy" and not csp:
            continue
        assert r.headers[name] == value, name
    assert "camera=()" in r.headers["permissions-policy"]
    assert "strict-transport-security" not in r.headers


def test_headers_on_api_json_probes_and_errors(client, alice):
    assert_base(alice.get("/users/me"))
    assert_base(client.get("/healthz"))
    assert_base(client.get("/api/v1/healthz"))
    assert_base(client.get("/api/v1/users/me"))  # 401 without a token
    assert_base(client.get("/api/v1/nonexistent"))  # 404
    assert_base(client.post("/api/v1/auth/login", content=b"{"))  # 422 or 400


def test_authenticated_json_is_never_cached(alice):
    r = alice.get("/users/me")
    assert r.headers["cache-control"] == "no-store"


def test_body_limit_413_carries_the_headers_too(client):
    r = client.post("/api/v1/auth/login", content=b"x" * (9 * 1024 * 1024))
    assert r.status_code == 413
    assert_base(r)
    assert r.headers["cache-control"] == "no-store"


def test_routes_keep_their_own_cache_control(alice):
    up = alice.post(
        "/attachments?purpose=message", content=b"0123456789", headers={"Content-Type": "application/octet-stream"}
    )
    assert up.status_code == 201
    got = alice.get(f"/attachments/{up.json()['id']}")
    assert got.headers["cache-control"] == "private, no-store"
    assert got.headers["x-content-type-options"] == "nosniff"
    assert got.headers["content-security-policy"] == BASE["content-security-policy"]


def test_docs_page_gets_a_relaxed_csp_and_everything_else_does_not(client):
    page = client.get("/api/v1/docs")
    assert page.status_code == 200
    csp = page.headers["content-security-policy"]
    assert csp != BASE["content-security-policy"] and "frame-ancestors 'none'" in csp
    assert "default-src 'none'" in csp
    # every external host the page loads must be allowed by the policy
    hosts = set(re.findall(r"https://([a-z0-9.\-]+)/", page.text))
    assert hosts, "the Swagger UI page is expected to load CDN assets"
    for host in hosts:
        assert f"https://{host}" in csp, host
    assert "'unsafe-inline'" in csp  # the page starts with one inline script
    assert page.headers["x-frame-options"] == "DENY"
    spec = client.get("/api/v1/openapi.json")
    assert spec.status_code == 200
    assert spec.headers["content-security-policy"] == BASE["content-security-policy"]


def test_hsts_only_when_enabled(make_app):
    _app, off = make_app()
    assert "strict-transport-security" not in off.get("/healthz").headers
    _app, on = make_app(hsts=True)
    assert on.get("/healthz").headers["strict-transport-security"] == "max-age=31536000"
    assert on.get("/api/v1/nonexistent").headers["strict-transport-security"] == "max-age=31536000"


def test_websocket_handshake_is_untouched(client, alice):
    with client.websocket_connect("/api/v1/ws", headers=alice.headers) as ws:
        assert ws.receive_json()

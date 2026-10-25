"""GET /metrics: Prometheus text format, no per-user labels, local or administrator access only (else 404)."""

import re

import pytest
from fastapi.testclient import TestClient

LINE = re.compile(r'^([a-z_:][a-z0-9_:]*)(\{(?:[^"}]|"(?:[^"\\]|\\.)*")*\})? (-?[0-9.]+(e[+-]?[0-9]+)?|\+Inf)$')


@pytest.fixture
def local(app) -> TestClient:
    return TestClient(app, client=("127.0.0.1", 50000))


def samples(text: str) -> list[tuple[str, dict, str]]:
    out = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        m = LINE.match(line)
        assert m, f"not a valid sample line: {line!r}"
        labels = dict(re.findall(r'(\w+)="((?:[^"\\]|\\.)*)"', m.group(2) or ""))
        out.append((m.group(1), labels, m.group(3)))
    return out


def value(text: str, name: str, **labels) -> float:
    found = [float(v) for n, lab, v in samples(text) if n == name and all(lab.get(k) == w for k, w in labels.items())]
    assert len(found) == 1, (name, labels, found)
    return found[0]


def test_loopback_without_forwarding_may_scrape(local):
    r = local.get("/metrics")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/plain; version=0.0.4")
    assert "# TYPE familychat_http_requests_total counter" in r.text
    assert samples(r.text)  # every line is valid exposition format


def test_ipv6_loopback_may_scrape(app):
    assert TestClient(app, client=("::1", 50000)).get("/metrics").status_code == 200


@pytest.mark.parametrize("header", ["x-forwarded-for", "forwarded", "x-real-ip"])
def test_loopback_behind_an_unknown_proxy_is_not_local(local, header):
    # a proxy on the same machine (Caddy) forwards remote clients from 127.0.0.1: it must not open /metrics
    value_ = "for=203.0.113.5" if header == "forwarded" else "203.0.113.5"
    assert local.get("/metrics", headers={header: value_}).status_code == 404


def test_forged_forwarded_header_cannot_claim_loopback(app):
    remote = TestClient(app, client=("203.0.113.9", 50000))
    assert remote.get("/metrics").status_code == 404
    assert remote.get("/metrics", headers={"X-Forwarded-For": "127.0.0.1"}).status_code == 404


def test_trusted_proxy_forwarding_a_remote_client_is_refused(make_app):
    app, _ = make_app(trusted_proxies="172.16.0.0/12")
    proxy = TestClient(app, client=("172.16.0.9", 50000))
    assert proxy.get("/metrics", headers={"X-Forwarded-For": "203.0.113.5"}).status_code == 404
    # the proxy appends the real peer, so a client-supplied 127.0.0.1 does not count
    assert proxy.get("/metrics", headers={"X-Forwarded-For": "127.0.0.1, 203.0.113.5"}).status_code == 404
    assert proxy.get("/metrics", headers={"X-Forwarded-For": "127.0.0.1"}).status_code == 200  # truly local client


def test_remote_needs_an_administrator_token(client, admin, alice):
    assert client.get("/metrics").status_code == 404
    assert client.get("/metrics", headers={"Authorization": "Bearer nonsense"}).status_code == 404
    assert client.get("/metrics", headers=alice.headers).status_code == 404  # signed in, but not an admin
    r = client.get("/metrics", headers=admin.headers)
    assert r.status_code == 200 and "familychat_uptime_seconds" in r.text
    assert client.get("/metrics").json()["code"] == "not_found"


def test_a_suspended_or_demoted_admin_token_stops_working(client, admin, factory):
    other = factory.user("other", admin=True)
    assert admin.patch(f"/admin/users/{other.id}", {"is_admin": False}).status_code == 200
    assert client.get("/metrics", headers=other.headers).status_code == 404


def test_request_counts_and_latency_use_route_templates(client, local, admin, alice):
    for _ in range(3):
        assert alice.get(f"/users/{admin.id}/devices").status_code == 200
    assert alice.get("/users/00000000-0000-4000-8000-000000000000/devices").status_code == 404
    assert client.get("/api/v1/nothing/here/123").status_code == 404
    text = local.get("/metrics").text
    route = "/api/v1/users/{user_id}/devices"
    assert value(text, "familychat_http_requests_total", method="GET", route=route, status="200") == 3
    assert value(text, "familychat_http_requests_total", method="GET", route=route, status="404") == 1
    assert value(text, "familychat_http_requests_total", route="unmatched", status="404") == 1
    count = value(text, "familychat_http_request_duration_seconds_count", route=route, status="200")
    assert count == 3
    assert value(text, "familychat_http_request_duration_seconds_bucket", route=route, status="200", le="+Inf") == 3
    assert value(text, "familychat_http_request_duration_seconds_sum", route=route, status="200") > 0
    buckets = [
        float(v)
        for n, lab, v in samples(text)
        if n == "familychat_http_request_duration_seconds_bucket"
        and lab.get("route") == route
        and lab["status"] == "200"
    ]
    assert buckets == sorted(buckets)  # cumulative


def test_no_ids_or_names_leak_into_labels(local, admin, alice, bob):
    alice.get(f"/users/{bob.id}/devices")
    text = local.get("/metrics").text
    for secret in (alice.id, bob.id, admin.id, "alice", "bob", alice.access):
        assert secret not in text
    names = {k for _n, lab, _v in samples(text) for k in lab}
    assert names <= {"method", "route", "status", "le", "version"}


def test_gauges(local, app, client, alice, bob, factory):
    cid = factory.direct(alice, bob)
    alice.send(cid, "queued")  # an unacknowledged event for bob's device
    before = value(local.get("/metrics").text, "familychat_event_queue_depth")
    assert before >= 1
    with client.websocket_connect("/api/v1/ws", headers=bob.headers) as ws:
        ws.receive_json()
        text = local.get("/metrics").text
        assert value(text, "familychat_websocket_connections") == 1
    assert value(local.get("/metrics").text, "familychat_websocket_connections") == 0
    text = local.get("/metrics").text
    assert value(text, "familychat_disk_free_bytes") > 0
    assert value(text, "familychat_push_queue_depth") >= 0
    assert value(text, "familychat_database_size_bytes") > 0
    assert 'familychat_build_info{version="' in text
    bob.ack(bob.sync()["next_cursor"])
    assert value(local.get("/metrics").text, "familychat_event_queue_depth") < before


def test_metrics_is_not_part_of_the_api_document(app):
    assert not [p for p in app.openapi()["paths"] if "metrics" in p]


def test_made_up_methods_and_urls_do_not_multiply_the_series(app, local):
    import random
    import string

    rnd = random.Random(7)
    for i in range(1000):
        method = "".join(rnd.choices(string.ascii_uppercase, k=rnd.randint(3, 12)))
        local.request(method, f"/api/v1/nothing-{i}/{rnd.random()}")
    text = local.get("/metrics").text
    series = [lab for n, lab, _ in samples(text) if n == "familychat_http_requests_total"]
    assert len(series) <= 10
    assert {lab["method"] for lab in series} <= {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "OTHER"}
    assert {lab["route"] for lab in series if lab["method"] == "OTHER"} == {"unmatched"}
    assert value(text, "familychat_http_requests_total", method="OTHER", route="unmatched", status="404") >= 900

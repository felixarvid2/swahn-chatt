"""X-Request-ID: accepted from the client only in a safe shape, returned, logged and put in error bodies."""

import io
import json
import logging
import re

import pytest
from fastapi.testclient import TestClient

from app.logging_setup import JsonFormatter
from app.request_id import current_request_id, pick_request_id

GENERATED_RE = re.compile(r"^[0-9a-f]{32}$")


def test_generated_when_the_client_sends_none(client):
    r = client.get("/api/v1/healthz")
    assert GENERATED_RE.match(r.headers["X-Request-ID"])
    assert client.get("/api/v1/healthz").headers["X-Request-ID"] != r.headers["X-Request-ID"]


@pytest.mark.parametrize("value", ["abcdefgh", "req-1234.ABC_xyz", "a" * 64, "0123456789abcdef0123456789abcdef"])
def test_valid_client_value_is_propagated(client, value):
    assert client.get("/api/v1/healthz", headers={"X-Request-ID": value}).headers["X-Request-ID"] == value


@pytest.mark.parametrize(
    "value",
    ["short", "a" * 65, "has space in it", "semi;colon-1234", "new\tline-12345", "ünicode-12345", "a/b/c/d/e/f"],
)
def test_invalid_client_value_is_replaced(client, value):
    got = client.get("/api/v1/healthz", headers={"X-Request-ID": value.encode("latin-1")}).headers["X-Request-ID"]
    assert got != value and GENERATED_RE.match(got)


def test_pick_request_id_rules():
    assert pick_request_id("valid-id-1") == "valid-id-1"
    assert pick_request_id("x" * 7) != "x" * 7
    assert pick_request_id("good-id-1\n") != "good-id-1\n"  # `$` must not accept a trailing newline
    assert pick_request_id(None)


def test_error_bodies_carry_the_request_id(client, alice):
    rid = "trace-0001-abcd"
    cases = [
        client.get("/api/v1/users/me", headers={"X-Request-ID": rid}),  # 401
        alice.get("/conversations/00000000-0000-4000-8000-000000000000", headers={"X-Request-ID": rid}),  # 404
        alice.post("/conversations", {"type": "nonsense"}, headers={"X-Request-ID": rid}),  # 422
        alice.get("/nope", headers={"X-Request-ID": rid}),  # routing 404
    ]
    assert [r.status_code for r in cases] == [401, 404, 422, 404]
    for r in cases:
        assert r.json()["request_id"] == rid and r.headers["X-Request-ID"] == rid
        assert {"code", "message"} <= set(r.json())


def test_generated_id_matches_between_header_and_body(client):
    r = client.post("/api/v1/auth/login", json={"username": "ghost", "password": "x", "device_name": "d"})
    assert r.status_code == 401 and r.json()["request_id"] == r.headers["X-Request-ID"]


def test_success_bodies_are_unchanged(alice):
    assert "request_id" not in alice.get("/users/me").json()


def test_oversized_body_error_has_the_id(alice):
    r = alice.post(
        "/messages",
        content=b"x" * (9 * 1024 * 1024),
        headers={"Content-Type": "application/json", "X-Request-ID": "big-body-12"},
    )
    assert r.status_code == 413
    assert r.json()["request_id"] == "big-body-12" and r.headers["X-Request-ID"] == "big-body-12"


def test_unhandled_errors_return_500_with_the_id(app):
    @app.get("/api/v1/_boom")
    def boom():
        raise RuntimeError("secret detail")

    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.get("/api/v1/_boom", headers={"X-Request-ID": "boom-id-1234"})
    assert r.status_code == 500
    assert r.json() == {"code": "internal_error", "message": "internal server error", "request_id": "boom-id-1234"}
    assert r.headers["X-Request-ID"] == "boom-id-1234"


@pytest.fixture
def json_log():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.addHandler(handler)
    yield lambda: [json.loads(line) for line in stream.getvalue().splitlines()]
    root.removeHandler(handler)


def test_every_log_line_of_a_request_has_its_id(app, client, json_log):
    log = logging.getLogger("swahnchat.test")

    @app.get("/api/v1/_sync_route")
    def sync_route():  # runs in the threadpool
        log.info("from a sync endpoint")
        return {"seen": current_request_id()}

    @app.get("/api/v1/_async_route")
    async def async_route():
        log.info("from an async endpoint")
        return {"seen": current_request_id()}

    log.info("outside any request")
    a = client.get("/api/v1/_sync_route", headers={"X-Request-ID": "sync-req-0001"})
    b = client.get("/api/v1/_async_route", headers={"X-Request-ID": "async-req-001"})
    assert a.json() == {"seen": "sync-req-0001"} and b.json() == {"seen": "async-req-001"}

    lines = json_log()
    by_msg = {(entry["msg"], entry.get("request_id")) for entry in lines}
    assert ("outside any request", None) in by_msg
    assert ("from a sync endpoint", "sync-req-0001") in by_msg
    assert ("from an async endpoint", "async-req-001") in by_msg
    access = [e for e in lines if e["msg"] == "request"]
    assert {e["request_id"] for e in access} == {"sync-req-0001", "async-req-001"}
    assert all(
        e["request_id"] for e in lines if e["logger"].startswith("swahnchat.test") and e["msg"] != "outside any request"
    )


def test_the_id_does_not_leak_between_requests(client):
    client.get("/api/v1/healthz", headers={"X-Request-ID": "first-req-001"})
    assert current_request_id() is None

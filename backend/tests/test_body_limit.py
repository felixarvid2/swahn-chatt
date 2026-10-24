"""The 8 MiB JSON body limit applies to everything except POST /api/v1/attachments."""

import pytest

from app.main import JSON_BODY_LIMIT

JSON = {"content-type": "application/json"}
BIG = b"[" + b"0," * (JSON_BODY_LIMIT // 2 + 10) + b"0]"  # just over the limit


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("PATCH", "/api/v1/conversations/attachments"),  # last segment "attachments" used to skip the limit
        ("PATCH", "/api/v1/conversations/attachments/"),
        ("PATCH", "/api/v1/conversations/x"),
        ("PUT", "/api/v1/attachments"),
        ("PATCH", "/api/v1/attachments/"),
        ("POST", "/api/v1/attachments/x"),
        ("POST", "/api/v1/auth/login"),
    ],
)
def test_oversized_body_rejected_before_authentication(client, method, path):
    r = client.request(method, path, content=BIG, headers=JSON)
    assert r.status_code == 413 and r.json()["code"] == "payload_too_large"


def test_oversized_chunked_body_without_content_length_rejected(client):
    def chunks():
        for _ in range(JSON_BODY_LIMIT // 65536 + 2):
            yield b"0" * 65536

    r = client.patch("/api/v1/conversations/attachments", content=chunks(), headers=JSON)
    assert r.status_code == 413 and r.json()["code"] == "payload_too_large"


def test_upload_endpoint_is_still_exempt(client, alice):
    # POST /attachments (with or without a trailing slash) is limited by its own, larger, limit instead.
    big = b"\0" * (JSON_BODY_LIMIT + 1024)
    h = {**alice.headers, "content-type": "application/octet-stream"}
    for path in ("/api/v1/attachments?purpose=message", "/api/v1/attachments/?purpose=message"):
        r = client.post(path, content=big, headers=h, follow_redirects=True)
        assert r.status_code == 201, r.text

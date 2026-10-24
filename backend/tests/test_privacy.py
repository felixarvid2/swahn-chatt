"""The server stores only what clients send: opaque ciphertext, public keys and metadata."""

import base64
import logging
import os
import uuid

from sqlalchemy import inspect, text

from tests.conftest import b64

# Plaintext that exists only on the "phones" (this test); it is never put on the wire.
SECRET_TEXT = "the secret plaintext pancake recipe 8f3a"


def test_stored_database_holds_envelope_bytes_verbatim_and_no_plaintext(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    alice2 = factory.login(alice, "tablet")
    sent = {}  # device_id -> exact bytes the client sent
    for i in range(3):
        mid = str(uuid.uuid4())
        envs = []
        for d in alice.member_devices(cid):
            blob = os.urandom(200 + i)  # opaque "ciphertext"
            sent[(mid, d["id"])] = blob
            envs.append({"device_id": d["id"], "type": "prekey" if i == 0 else "whisper", "body": b64(blob)})
        assert (
            alice.post(
                "/messages", {"id": mid, "conversation_id": cid, "kind": "message", "client_ts": 5, "envelopes": envs}
            ).status_code
            == 201
        )
    assert bob.events("message") and alice2.events("message")

    with app.state.engine.connect() as conn:
        rows = {
            (m, d): bytes(b)
            for m, d, b in conn.execute(text("SELECT message_id, device_id, body FROM message_envelopes"))
        }
    assert rows == sent  # byte-exact, nothing added, nothing transformed

    # the schema has exactly one column that can hold message content, and it is binary
    columns = {
        (t, c["name"]): str(c["type"]).upper()
        for t in inspect(app.state.engine).get_table_names()
        for c in inspect(app.state.engine).get_columns(t)
    }
    for (table, name), typ in columns.items():
        if name in {"body", "text", "content", "plaintext", "message", "ciphertext"}:
            assert (table, name) == ("message_envelopes", "body") and typ in {"BLOB", "BYTEA"}

    if not app.state.settings.is_sqlite:
        return
    # no plaintext anywhere in the files on disk (main db, WAL), neither raw nor as base64
    path = app.state.settings.sqlite_path
    app.state.engine.dispose()
    blobs = b"".join(p.read_bytes() for p in path.parent.glob("familychat.db*"))
    assert SECRET_TEXT.encode() not in blobs
    assert b64(SECRET_TEXT.encode()).encode() not in blobs
    for blob in list(sent.values())[:3]:
        assert base64.b64encode(blob) not in blobs  # ciphertext is stored as bytes, not re-encoded


def test_logs_never_contain_secrets_or_message_bodies(factory, app, caplog):
    caplog.set_level(logging.DEBUG)
    code = factory.invite()
    a = factory.user("alice")
    b = factory.user("bob")
    # use the redeem path with a known invite code too
    r = a.client.post(
        "/api/v1/auth/invite/redeem",
        json={
            "code": code,
            "username": "carol",
            "password": "carols-secret-pw",
            "display_name": "C",
            "device_name": "d",
        },
    )
    refresh = r.json()["refresh_token"]
    a.client.post("/api/v1/auth/login", json={"username": "alice", "password": "wrong-password", "device_name": "d"})
    cid = factory.direct(a, b)
    body = b64(SECRET_TEXT.encode())
    a.post(
        "/messages",
        {
            "id": str(uuid.uuid4()),
            "conversation_id": cid,
            "kind": "message",
            "client_ts": 1,
            "envelopes": [{"device_id": b.device_id, "type": "whisper", "body": body}],
        },
    )
    a.client.get(f"/api/v1/sync?access_token={a.access}", headers=a.headers)
    from app.logging_setup import JsonFormatter

    fmt = JsonFormatter()
    text = "\n".join(fmt.format(rec) for rec in caplog.records)
    assert '"msg": "request"' in text  # we do log requests...
    for secret in (
        a.access,
        b.access,
        a.refresh,
        refresh,
        code,
        "carols-secret-pw",
        "wrong-password",
        a.password,
        body,
        SECRET_TEXT,
    ):
        assert secret not in text
    assert "access_token=" not in text  # query strings are not logged


def test_json_log_format():
    import json

    from app.logging_setup import JsonFormatter

    rec = logging.LogRecord("x", logging.INFO, __file__, 1, "hello %s", ("w",), None)
    rec.fields = {"status": 200}
    out = json.loads(JsonFormatter().format(rec))
    assert out["msg"] == "hello w" and out["status"] == 200 and out["level"] == "INFO" and out["ts"].endswith("Z")

"""Resumable uploads for large message attachments: sessions, chunks, resume, quota reservation, expiry, safety."""

import hashlib
import os

import pytest
from sqlalchemy import func, select, update

from app import clock
from app.models import Attachment, UploadSession, User
from app.services import uploads
from app.services.maintenance import prune
from tests.conftest import Factory

OCTET = {"Content-Type": "application/octet-stream"}
MIB = 1024 * 1024


def start(actor, data: bytes, sha: bool = True, **extra):
    body = {"size": len(data), "purpose": "message", **extra}
    if sha:
        body["sha256"] = hashlib.sha256(data).hexdigest()
    return actor.post("/attachments/uploads", body)


def chunk(actor, upload_id, offset, data, **headers):
    return actor.req(
        "PATCH",
        f"/attachments/uploads/{upload_id}",
        content=data,
        headers={**OCTET, "Upload-Offset": str(offset), **headers},
    )


def part_file(app, upload_id):
    return uploads.part_path(app.state.settings, upload_id)


def expire(app, upload_id):
    with app.state.session_factory() as db:
        db.execute(update(UploadSession).where(UploadSession.id == upload_id).values(expires_at=clock.now_ms() - 1))
        db.commit()


def run_prune(app):
    with app.state.session_factory() as db:
        return prune(db, app.state.settings)


# ---------------------------------------------------------------- the happy path


def test_upload_in_chunks_becomes_a_normal_attachment(app, alice, bob, factory):
    data = os.urandom(300_000)
    r = start(alice, data)
    assert r.status_code == 201
    session = r.json()
    assert session["offset"] == 0 and session["size"] == len(data) and session["max_chunk_bytes"] == 8 * MIB
    assert session["expires_at"] > clock.now_ms() + 23 * 3600 * 1000
    uid = session["upload_id"]

    r = chunk(alice, uid, 0, data[:100_000])
    assert r.status_code == 200 and r.json() == {"offset": 100_000} and r.headers["Upload-Offset"] == "100000"
    assert chunk(alice, uid, 100_000, data[100_000:250_000]).json() == {"offset": 250_000}
    r = chunk(alice, uid, 250_000, data[250_000:])
    assert r.status_code == 201
    att = r.json()
    assert set(att) == {"id", "purpose", "content_type", "size", "sha256", "created_at"}  # same shape as POST
    assert (
        att["purpose"] == "message" and att["size"] == len(data) and att["sha256"] == hashlib.sha256(data).hexdigest()
    )
    assert att["content_type"] == "application/octet-stream"
    assert not part_file(app, uid).exists()  # the temp file moved into place

    # it is an ordinary attachment: downloadable, referenceable by messages, shared with the conversation
    assert alice.get(f"/attachments/{att['id']}").content == data
    cid = factory.direct(alice, bob)
    assert alice.send(cid, "big file", attachment_ids=[att["id"]]).status_code == 201
    assert bob.get(f"/attachments/{att['id']}").content == data
    with app.state.session_factory() as db:
        row = db.get(Attachment, att["id"])
        assert row.storage_path == f"{att['id'][:2]}/{att['id']}" and row.uploader_id == alice.id
        assert db.get(UploadSession, uid) is None
    assert alice.get(f"/attachments/uploads/{uid}").status_code == 404  # finished; the attachment id is the upload id


def test_a_single_chunk_upload_without_checksum(alice):
    data = b"tiny"
    uid = start(alice, data, sha=False).json()["upload_id"]
    r = chunk(alice, uid, 0, data)
    assert r.status_code == 201 and r.json()["size"] == 4


def test_get_and_head_report_the_offset(client, alice):
    data = os.urandom(5000)
    uid = start(alice, data).json()["upload_id"]
    chunk(alice, uid, 0, data[:1234])
    got = alice.get(f"/attachments/uploads/{uid}")
    assert got.status_code == 200 and got.json()["offset"] == 1234 and got.headers["Upload-Offset"] == "1234"
    assert got.headers["Upload-Length"] == "5000"
    head = alice.req("HEAD", f"/attachments/uploads/{uid}")
    assert head.status_code == 200 and head.headers["Upload-Offset"] == "1234" and head.content == b""


# ---------------------------------------------------------------- resuming and errors


def test_resume_after_an_interruption_even_with_a_half_written_chunk(app, alice):
    data = os.urandom(200_000)
    uid = start(alice, data).json()["upload_id"]
    chunk(alice, uid, 0, data[:80_000])
    # the connection died in the middle of the next chunk: bytes reached the file but the offset never advanced
    with open(part_file(app, uid), "ab") as f:
        f.write(b"\xff" * 33_333)
    offset = alice.get(f"/attachments/uploads/{uid}").json()["offset"]
    assert offset == 80_000
    r = chunk(alice, uid, offset, data[offset:])
    assert r.status_code == 201
    assert alice.get(f"/attachments/{r.json()['id']}").content == data


def test_wrong_offset_is_a_409_that_names_the_right_one(alice):
    data = os.urandom(1000)
    uid = start(alice, data).json()["upload_id"]
    chunk(alice, uid, 0, data[:400])
    for wrong in (0, 399, 401, 5000):
        r = chunk(alice, uid, wrong, data[400:])
        assert r.status_code == 409, wrong
        assert r.json()["code"] == "offset_mismatch" and r.json()["offset"] == 400
        assert r.headers["Upload-Offset"] == "400"
    assert alice.get(f"/attachments/uploads/{uid}").json()["offset"] == 400  # nothing changed
    assert chunk(alice, uid, 400, data[400:]).status_code == 201


def test_bad_requests(alice):
    data = b"x" * 100
    uid = start(alice, data).json()["upload_id"]
    path = f"/attachments/uploads/{uid}"
    assert alice.req("PATCH", path, content=data, headers=OCTET).status_code == 422  # no Upload-Offset
    assert chunk(alice, uid, "abc", data).status_code == 422
    assert chunk(alice, uid, -1, data).status_code == 422
    assert chunk(alice, uid, 0, b"").status_code == 422
    r = alice.req("PATCH", path, content=data, headers={"Upload-Offset": "0", "Content-Type": "text/plain"})
    assert r.status_code == 415
    assert alice.get(path).json()["offset"] == 0
    for bad in (
        {"size": 0, "purpose": "message"},
        {"size": -5, "purpose": "message"},
        {"size": 10, "purpose": "avatar"},
        {"size": 10},
        {"size": 10, "purpose": "message", "sha256": "xyz"},
    ):
        assert alice.post("/attachments/uploads", bad).status_code == 422, bad


def test_chunks_cannot_exceed_the_limit_or_the_announced_size(alice):
    big = 9 * MIB
    uid = alice.post("/attachments/uploads", {"size": big, "purpose": "message"}).json()["upload_id"]
    r = chunk(alice, uid, 0, b"a" * (8 * MIB + 1))
    assert r.status_code == 413 and r.json()["code"] == "payload_too_large"
    assert alice.get(f"/attachments/uploads/{uid}").json()["offset"] == 0
    r = chunk(alice, uid, 0, b"a" * (8 * MIB))  # exactly the maximum is fine
    assert r.status_code == 200 and r.json()["offset"] == 8 * MIB
    r = chunk(alice, uid, 8 * MIB, b"a" * (MIB + 1))  # more than the session has room for
    assert r.status_code == 413
    assert alice.get(f"/attachments/uploads/{uid}").json()["offset"] == 8 * MIB
    assert chunk(alice, uid, 8 * MIB, b"a" * MIB).status_code == 201


def test_a_failed_chunk_leaves_no_bytes_behind(app, alice):
    uid = alice.post("/attachments/uploads", {"size": 9 * MIB, "purpose": "message"}).json()["upload_id"]
    chunk(alice, uid, 0, b"a" * 1000)
    chunk(alice, uid, 1000, b"a" * (8 * MIB + 1))  # refused
    assert part_file(app, uid).stat().st_size == 1000


def test_oversize_uploads_are_refused_up_front(make_app):
    app, client = make_app(max_attachment_mb=1)
    alice = Factory(app, client).user("alice")
    r = alice.post("/attachments/uploads", {"size": MIB + 1, "purpose": "message"})
    assert r.status_code == 413 and r.json()["code"] == "payload_too_large"
    assert alice.post("/attachments/uploads", {"size": MIB, "purpose": "message"}).status_code == 201


def test_checksum_mismatch_discards_the_session(app, alice):
    data = os.urandom(2000)
    uid = alice.post("/attachments/uploads", {"size": 2000, "purpose": "message", "sha256": "0" * 64}).json()[
        "upload_id"
    ]
    chunk(alice, uid, 0, data[:1000])
    r = chunk(alice, uid, 1000, data[1000:])
    assert r.status_code == 422 and r.json()["code"] == "checksum_mismatch"
    assert alice.get(f"/attachments/uploads/{uid}").status_code == 404
    assert not part_file(app, uid).exists()
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(Attachment)) == 0


def test_checksum_comparison_ignores_case(alice):
    data = b"abc" * 100
    r = alice.post(
        "/attachments/uploads", {"size": 300, "purpose": "message", "sha256": hashlib.sha256(data).hexdigest().upper()}
    )
    assert chunk(alice, r.json()["upload_id"], 0, data).status_code == 201


# ---------------------------------------------------------------- ownership


def test_only_the_creator_can_touch_a_session(client, alice, bob):
    data = os.urandom(500)
    uid = start(alice, data).json()["upload_id"]
    path = f"/attachments/uploads/{uid}"
    assert bob.get(path).status_code == 404
    assert bob.req("HEAD", path).status_code == 404
    r = chunk(bob, uid, 0, data)
    assert r.status_code == 404 and r.json()["code"] == "not_found"
    assert chunk(alice, uid, 0, data[:10]).status_code == 200  # unaffected
    missing = "00000000-0000-4000-8000-000000000000"
    assert alice.get(f"/attachments/uploads/{missing}").status_code == 404
    assert client.get("/api/v1" + path).status_code == 401


@pytest.mark.parametrize("bad_id", ["..%2F..%2Fetc%2Fpasswd", "%2e%2e", "not-a-uuid", "a" * 36, "x/../y"])
def test_ids_that_are_not_uuids_never_reach_the_filesystem(alice, bad_id):
    assert alice.get(f"/attachments/uploads/{bad_id}").status_code == 404
    assert chunk(alice, bad_id, 0, b"data").status_code == 404


def test_temp_files_live_under_the_attachments_directory_and_are_named_by_id_only(app, alice):
    uid = start(alice, b"x" * 10).json()["upload_id"]
    path = part_file(app, uid)
    base = app.state.settings.attachments_dir.resolve()
    assert path.is_relative_to(base) and path.name == f"{uid}.part" and path.parent.name == "uploads"
    assert path.stat().st_mode & 0o077 == 0
    for evil in ("../x", "../../etc/passwd", "a/b", ""):
        with pytest.raises(Exception):  # noqa: B017 - ApiError(404)
            uploads.part_path(app.state.settings, evil)


# ---------------------------------------------------------------- quota


def test_quota_is_reserved_at_once_and_released_when_the_session_dies(make_app):
    app, client = make_app(user_quota_mb=1)
    f = Factory(app, client)
    alice, bob = f.user("alice"), f.user("bob")
    first = alice.post("/attachments/uploads", {"size": 600_000, "purpose": "message"})
    assert first.status_code == 201
    r = alice.post("/attachments/uploads", {"size": 600_000, "purpose": "message"})
    assert r.status_code == 413 and r.json()["code"] == "quota_exceeded"
    # the plain upload endpoint sees the reservation too
    r = alice.post("/attachments?purpose=message", content=b"y" * 600_000, headers=OCTET)
    assert r.status_code == 413 and r.json()["code"] == "quota_exceeded"
    assert alice.post("/attachments?purpose=message", content=b"y" * 100_000, headers=OCTET).status_code == 201
    # other users are not affected
    assert bob.post("/attachments/uploads", {"size": 900_000, "purpose": "message"}).status_code == 201
    # finishing converts the reservation into the stored size: no double counting
    uid = first.json()["upload_id"]
    assert chunk(alice, uid, 0, b"z" * 600_000).status_code == 201
    assert alice.post("/attachments/uploads", {"size": 300_000, "purpose": "message"}).status_code == 201
    assert alice.post("/attachments/uploads", {"size": 100_000, "purpose": "message"}).status_code == 413


def test_expired_sessions_are_refused_collected_and_free_their_quota(make_app):
    app, client = make_app(user_quota_mb=1)
    alice = Factory(app, client).user("alice")
    r = alice.post("/attachments/uploads", {"size": 900_000, "purpose": "message"})
    uid = r.json()["upload_id"]
    chunk(alice, uid, 0, b"q" * 5000)
    part = part_file(app, uid)
    assert part.exists()
    assert alice.post("/attachments/uploads", {"size": 900_000, "purpose": "message"}).status_code == 413
    expire(app, uid)
    assert alice.get(f"/attachments/uploads/{uid}").status_code == 404
    assert chunk(alice, uid, 5000, b"q").status_code == 404
    # the reservation of an expired session is already free; the hourly job removes row and file
    assert run_prune(app)["upload_sessions"] == 1
    assert not part.exists()
    with app.state.session_factory() as db:
        assert db.get(UploadSession, uid) is None
    assert alice.post("/attachments/uploads", {"size": 900_000, "purpose": "message"}).status_code == 201


def test_sessions_last_24_hours_not_less(app, alice):
    uid = start(alice, b"x" * 10).json()["upload_id"]
    with app.state.session_factory() as db:
        row = db.get(UploadSession, uid)
        assert 24 * 3600 * 1000 - 60_000 <= row.expires_at - row.created_at <= 24 * 3600 * 1000
    assert run_prune(app)["upload_sessions"] == 0  # a live session survives the maintenance run
    assert part_file(app, uid).exists()


def test_orphaned_temp_files_are_swept_but_live_ones_are_not(app, alice):
    uid = start(alice, b"x" * 10).json()["upload_id"]
    orphan = uploads.uploads_dir(app.state.settings) / "00000000-0000-4000-8000-0000000000aa.part"
    orphan.write_bytes(b"left over")
    fresh = uploads.uploads_dir(app.state.settings) / "00000000-0000-4000-8000-0000000000bb.part"
    fresh.write_bytes(b"in flight")
    old = clock.now_ms() / 1000 - 7200
    os.utime(orphan, (old, old))
    run_prune(app)
    assert not orphan.exists() and fresh.exists() and part_file(app, uid).exists()


def test_sessions_of_removed_accounts_are_collected(app, admin, alice):
    uid = start(alice, b"x" * 10).json()["upload_id"]
    assert admin.delete(f"/admin/users/{alice.id}").status_code == 204
    assert run_prune(app)["upload_sessions"] == 1
    assert not part_file(app, uid).exists()
    with app.state.session_factory() as db:
        assert db.get(User, alice.id).disabled_at is not None


def test_number_of_open_sessions_is_capped(alice):
    for _ in range(uploads.MAX_ACTIVE_PER_USER):
        assert alice.post("/attachments/uploads", {"size": 10, "purpose": "message"}).status_code == 201
    r = alice.post("/attachments/uploads", {"size": 10, "purpose": "message"})
    assert r.status_code == 429 and r.json()["code"] == "too_many_uploads"


def test_concurrent_chunks_of_one_session_are_refused(app, alice, client):
    uid = start(alice, b"x" * 10).json()["upload_id"]
    app.state.uploads_busy.add(uid)  # a chunk is being written right now
    r = chunk(alice, uid, 0, b"x" * 10)
    assert r.status_code == 409 and r.json()["code"] == "upload_busy"
    app.state.uploads_busy.discard(uid)
    assert chunk(alice, uid, 0, b"x" * 10).status_code == 201


def test_the_plain_upload_endpoint_still_works_unchanged(alice):
    r = alice.post("/attachments?purpose=message", content=b"plain", headers=OCTET)
    assert r.status_code == 201 and set(r.json()) == {"id", "purpose", "content_type", "size", "sha256", "created_at"}

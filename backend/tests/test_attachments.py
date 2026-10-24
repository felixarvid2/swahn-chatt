import hashlib
import os
import uuid
from pathlib import Path

from app import clock
from app.models import Attachment
from app.services.attachments import collect_garbage

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64
WEBP = b"RIFF\x24\x00\x00\x00WEBPVP8 " + b"\x00" * 64
OCTET = {"Content-Type": "application/octet-stream"}


def upload(actor, data, purpose="message", content_type="application/octet-stream", **headers):
    return actor.post(
        f"/attachments?purpose={purpose}", content=data, headers={"Content-Type": content_type, **headers}
    )


def stored_files(app):
    root = app.state.settings.attachments_dir
    return [p for p in root.rglob("*") if p.is_file()]


def test_upload_and_member_download(factory, alice, bob, carol, app):
    data = os.urandom(300_000)
    r = upload(alice, data, **{"X-Content-SHA256": hashlib.sha256(data).hexdigest()})
    assert r.status_code == 201, r.text
    att = r.json()
    assert att["size"] == len(data) and att["sha256"] == hashlib.sha256(data).hexdigest()
    assert att["purpose"] == "message" and att["content_type"] == "application/octet-stream"
    # stored under a random uuid name, never a client supplied one
    (path,) = stored_files(app)
    assert path.name == att["id"] and path.read_bytes() == data

    # before it is attached to a message only the uploader can read it
    assert alice.get(f"/attachments/{att['id']}").content == data
    assert bob.get(f"/attachments/{att['id']}").status_code == 404

    cid = factory.direct(alice, bob)
    assert alice.send(cid, "see file", attachment_ids=[att["id"]]).status_code == 201
    got = bob.get(f"/attachments/{att['id']}")
    assert got.status_code == 200 and got.content == data
    assert got.headers["content-type"] == "application/octet-stream"
    assert got.headers["x-content-type-options"] == "nosniff"
    assert carol.get(f"/attachments/{att['id']}").status_code == 404  # non-member
    ev = bob.events("message")[0]["data"]
    assert ev["attachment_ids"] == [att["id"]]
    assert bob.get(f"/attachments/{uuid.uuid4()}").status_code == 404


def test_download_requires_auth(client, alice):
    att = upload(alice, b"abc").json()
    assert client.get(f"/api/v1/attachments/{att['id']}").status_code == 401


def test_deleted_message_revokes_member_access(factory, alice, bob):
    att = upload(alice, b"secret bytes").json()
    cid = factory.direct(alice, bob)
    mid = alice.send(cid, "file", attachment_ids=[att["id"]]).json()["message"]["id"]
    assert bob.get(f"/attachments/{att['id']}").status_code == 200
    alice.send(cid, "", kind="delete", target_message_id=mid)
    assert bob.get(f"/attachments/{att['id']}").status_code == 404
    assert alice.get(f"/attachments/{att['id']}").status_code == 200


def test_removed_member_loses_access(factory, alice, bob, carol):
    att = upload(alice, b"group file").json()
    cid = factory.group(alice, [bob, carol])
    alice.send(cid, "file", attachment_ids=[att["id"]])
    assert carol.get(f"/attachments/{att['id']}").status_code == 200
    alice.delete(f"/conversations/{cid}/members/{carol.id}")
    assert carol.get(f"/attachments/{att['id']}").status_code == 404


def test_attaching_foreign_or_missing_attachments_is_rejected(factory, alice, bob):
    cid = factory.direct(alice, bob)
    theirs = upload(bob, b"bobs").json()
    assert alice.send(cid, "x", attachment_ids=[theirs["id"]]).status_code == 422
    assert alice.send(cid, "x", attachment_ids=[str(uuid.uuid4())]).status_code == 422
    avatar = upload(alice, PNG, "avatar", "image/png").json()
    assert alice.send(cid, "x", attachment_ids=[avatar["id"]]).status_code == 422


def test_range_requests(alice):
    data = bytes(range(256)) * 40
    att = upload(alice, data).json()
    url = f"/attachments/{att['id']}"
    r = alice.get(url, headers={"Range": "bytes=10-19"})
    assert r.status_code == 206 and r.content == data[10:20]
    assert r.headers["content-range"] == f"bytes 10-19/{len(data)}"
    r = alice.get(url, headers={"Range": "bytes=-5"})
    assert r.status_code == 206 and r.content == data[-5:]
    r = alice.get(url, headers={"Range": f"bytes={len(data) - 3}-"})
    assert r.status_code == 206 and r.content == data[-3:]
    assert alice.get(url, headers={"Range": f"bytes={len(data) + 10}-"}).status_code == 416
    assert alice.get(url).headers["accept-ranges"] == "bytes"


def test_streaming_upload_without_content_length(alice):
    def chunks():
        for i in range(30):
            yield bytes([i]) * 100_000

    r = alice.post("/attachments?purpose=message", content=chunks(), headers=OCTET)
    assert r.status_code == 201 and r.json()["size"] == 3_000_000
    assert alice.get(f"/attachments/{r.json()['id']}").content == b"".join(bytes([i]) * 100_000 for i in range(30))


def test_size_limit(make_app):
    from tests.conftest import Factory

    app, client = make_app(max_attachment_mb=1)
    a = Factory(app, client).user("alice")
    ok = upload(a, b"x" * 1024 * 1024)
    assert ok.status_code == 201
    too_big = upload(a, b"x" * (1024 * 1024 + 1))
    assert too_big.status_code == 413 and too_big.json()["code"] == "payload_too_large"

    def chunks():  # no Content-Length: the server must count while streaming
        for _ in range(12):
            yield b"y" * 100_000

    r = a.post("/attachments?purpose=message", content=chunks(), headers=OCTET)
    assert r.status_code == 413 and r.json()["code"] == "payload_too_large"
    assert len(stored_files(app)) == 1  # nothing left behind by the rejected uploads
    assert list((app.state.settings.attachments_dir / ".tmp").iterdir()) == []


def test_user_quota(make_app):
    from tests.conftest import Factory

    app, client = make_app(user_quota_mb=1)
    f = Factory(app, client)
    a, b = f.user("alice"), f.user("bob")
    assert upload(a, b"x" * 600_000).status_code == 201
    r = upload(a, b"x" * 600_000)
    assert r.status_code == 413 and r.json()["code"] == "quota_exceeded"
    assert upload(a, b"x" * 400_000).status_code == 201
    assert upload(b, b"x" * 600_000).status_code == 201  # quotas are per user
    assert len(stored_files(app)) == 3


def test_checksum_mismatch(alice, app):
    r = upload(alice, b"hello", **{"X-Content-SHA256": "0" * 64})
    assert r.status_code == 422 and r.json()["code"] == "checksum_mismatch"
    assert stored_files(app) == []
    assert (
        upload(alice, b"hello", **{"X-Content-SHA256": hashlib.sha256(b"hello").hexdigest().upper()}).status_code == 201
    )
    assert upload(alice, b"hello", **{"X-Content-SHA256": "xyz"}).status_code == 422


def test_content_type_and_empty_body(alice):
    assert upload(alice, b"data", content_type="image/png").status_code == 415
    assert upload(alice, b"data", content_type="text/html").status_code == 415
    assert upload(alice, b"", content_type="application/octet-stream").status_code == 422
    r = alice.post("/attachments?purpose=bogus", content=b"x", headers=OCTET)
    assert r.status_code == 422


def test_avatar_magic_bytes_and_limits(factory, alice, bob):
    for data, ctype in ((PNG, "image/png"), (JPEG, "image/jpeg"), (WEBP, "image/webp")):
        r = upload(alice, data, "avatar", ctype)
        assert r.status_code == 201, (ctype, r.text)
        assert r.json()["content_type"] == ctype
    for data, ctype in ((JPEG, "image/png"), (PNG, "image/jpeg"), (b"<svg/>", "image/webp"), (PNG, "image/gif")):
        r = upload(alice, data, "avatar", ctype)
        assert r.status_code == 415, (ctype, r.status_code)
    assert upload(alice, b"MZ" + b"\x00" * 50, "avatar", "application/octet-stream").status_code == 415
    big = PNG + b"\x00" * (2 * 1024 * 1024)
    assert upload(alice, big, "avatar", "image/png").status_code == 413

    avatar = upload(alice, PNG, "avatar", "image/png").json()
    # avatars are visible to every authenticated user
    got = bob.get(f"/attachments/{avatar['id']}")
    assert got.status_code == 200 and got.headers["content-type"] == "image/png"
    # only the uploader can use it as their avatar
    assert bob.patch("/users/me", {"avatar_attachment_id": avatar["id"]}).status_code == 422
    r = alice.patch("/users/me", {"avatar_attachment_id": avatar["id"]})
    assert r.status_code == 200 and r.json()["avatar_attachment_id"] == avatar["id"]
    assert any(e["type"] == "user_updated" and e["data"]["avatar_attachment_id"] == avatar["id"] for e in bob.events())
    assert alice.patch("/users/me", {"avatar_attachment_id": None}).json()["avatar_attachment_id"] is None
    # group avatars
    cid = factory.group(alice, [bob])
    assert alice.patch(f"/conversations/{cid}", {"avatar_attachment_id": avatar["id"]}).status_code == 200
    plain = upload(alice, b"x", "message").json()
    assert alice.patch(f"/conversations/{cid}", {"avatar_attachment_id": plain["id"]}).status_code == 422


def test_garbage_collection_of_unreferenced_attachments(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    kept = upload(alice, b"kept").json()
    alice.send(cid, "x", attachment_ids=[kept["id"]])
    orphan = upload(alice, b"orphan").json()
    fresh = upload(alice, b"fresh").json()
    avatar = upload(alice, PNG, "avatar", "image/png").json()
    used_avatar = upload(alice, PNG, "avatar", "image/png").json()
    alice.patch("/users/me", {"avatar_attachment_id": used_avatar["id"]})
    old = clock.now_ms() - 25 * 3600 * 1000
    with app.state.session_factory() as db:
        for att in db.query(Attachment):
            if att.id != fresh["id"]:
                att.created_at = old
        db.commit()
        removed = collect_garbage(db, app.state.settings)
        left = {a.id for a in db.query(Attachment)}
    assert removed == 2
    assert left == {kept["id"], fresh["id"], used_avatar["id"]}
    names = {p.name for p in stored_files(app)}
    assert names == left
    assert orphan["id"] not in names and avatar["id"] not in names
    assert Path(app.state.settings.attachments_dir).is_dir()

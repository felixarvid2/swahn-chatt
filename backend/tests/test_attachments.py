import hashlib
import os
import uuid
from pathlib import Path

import pytest

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


# --- tampered storage_path (e.g. from a modified restored backup) must never leave the attachments dir


def _tamper(app, att_id, storage_path):
    with app.state.session_factory() as db:
        db.get(Attachment, att_id).storage_path = storage_path
        db.commit()


def test_download_refuses_tampered_storage_paths(alice, app, tmp_path):
    secret = app.state.settings.data_dir / "outside.txt"
    secret.write_bytes(b"top secret")
    att = upload(alice, b"abc").json()
    own = f"{att['id'][:2]}/{att['id']}"
    other = str(uuid.uuid4())
    for bad in (
        "../outside.txt",
        "../familychat.db",
        str(secret),  # absolute
        f"{att['id'][:2]}/../../outside.txt",
        f"{other[:2]}/{other}",  # well formed, but not this attachment's own file
        f"zz/{att['id']}",
        own + "/",
        "",
    ):
        _tamper(app, att["id"], bad)
        r = alice.get(f"/attachments/{att['id']}")
        assert r.status_code == 404, bad
    _tamper(app, att["id"], own)
    assert alice.get(f"/attachments/{att['id']}").content == b"abc"
    # a symlink inside the directory that points outside is refused as well
    link = app.state.settings.attachments_dir / own
    link.unlink()
    link.symlink_to(secret)
    assert alice.get(f"/attachments/{att['id']}").status_code == 404


def test_garbage_collection_never_deletes_outside_the_attachments_dir(alice, app):
    db_file = app.state.settings.data_dir / "familychat.db"  # (a stand-in file on PostgreSQL)
    db_file.touch()
    victim = app.state.settings.data_dir / "outside.txt"
    victim.write_bytes(b"keep me")
    att = upload(alice, b"abc").json()
    own = app.state.settings.attachments_dir / att["id"][:2] / att["id"]
    with app.state.session_factory() as db:
        a = db.get(Attachment, att["id"])
        a.created_at = clock.now_ms() - 3 * 24 * 3600 * 1000
        a.storage_path = "../../familychat.db"
        db.commit()
        collect_garbage(db, app.state.settings)
        a.storage_path = "../outside.txt"
        db.commit()
        collect_garbage(db, app.state.settings)
        assert db.get(Attachment, att["id"]) is not None  # row left for the operator to look at
    assert db_file.exists() and victim.read_bytes() == b"keep me" and own.exists()


@pytest.mark.sqlite_only
def test_restore_rejects_a_database_with_tampered_storage_paths(factory, alice, app, tmp_path):
    from app.services.backup import BackupError, create_backup, restore_backup
    from tests.conftest import make_settings

    att = upload(alice, b"abc").json()
    _tamper(app, att["id"], "../../../etc/passwd")
    archive = create_backup(app.state.settings, tmp_path / "b")
    target = tmp_path / "restored"
    with pytest.raises(BackupError, match="invalid storage path"):
        restore_backup(make_settings(target), archive)
    assert not (target / "familychat.db").exists()


# --- avatar garbage collection (previous avatars after a change)


def _age(app, *attachment_ids, hours=25):
    with app.state.session_factory() as db:
        for aid in attachment_ids:
            db.get(Attachment, aid).created_at = clock.now_ms() - hours * 3600 * 1000
        db.commit()


def _prune(app):
    from app.services.maintenance import prune

    with app.state.session_factory() as db:
        return prune(db, app.state.settings)


def _exists(app, att):
    with app.state.session_factory() as db:
        row = db.get(Attachment, att["id"]) is not None
    on_disk = (app.state.settings.attachments_dir / att["id"][:2] / att["id"]).exists()
    assert row == on_disk, "row and file must go together"
    return row


def test_replaced_user_avatar_is_collected_by_the_maintenance_job(alice, app):
    first = upload(alice, PNG, "avatar", "image/png").json()
    second = upload(alice, PNG, "avatar", "image/png").json()
    assert alice.patch("/users/me", {"avatar_attachment_id": first["id"]}).status_code == 200
    assert alice.patch("/users/me", {"avatar_attachment_id": second["id"]}).status_code == 200
    _age(app, first["id"], second["id"])
    assert _prune(app)["attachments"] == 1
    assert not _exists(app, first) and _exists(app, second)
    assert alice.get(f"/attachments/{first['id']}").status_code == 404
    assert alice.get(f"/attachments/{second['id']}").status_code == 200
    assert alice.get("/users/me").json()["avatar_attachment_id"] == second["id"]


def test_cleared_user_avatar_and_never_used_uploads_are_collected(alice, app):
    kept_for_now = upload(alice, PNG, "avatar", "image/png").json()
    cleared = upload(alice, PNG, "avatar", "image/png").json()
    never_used = upload(alice, PNG, "avatar", "image/png").json()
    alice.patch("/users/me", {"avatar_attachment_id": cleared["id"]})
    alice.patch("/users/me", {"avatar_attachment_id": None})
    _age(app, cleared["id"], never_used["id"])  # kept_for_now stays young
    assert _prune(app)["attachments"] == 2
    assert _exists(app, kept_for_now) and not _exists(app, cleared) and not _exists(app, never_used)


def test_group_avatar_replaced_or_removed_is_collected_but_a_used_one_stays(factory, alice, bob, app):
    old = upload(alice, PNG, "avatar", "image/png").json()
    new = upload(alice, PNG, "avatar", "image/png").json()
    gid = factory.group(alice, [bob])
    assert alice.patch(f"/conversations/{gid}", {"avatar_attachment_id": old["id"]}).status_code == 200
    assert alice.patch(f"/conversations/{gid}", {"avatar_attachment_id": new["id"]}).status_code == 200
    _age(app, old["id"], new["id"])
    assert _prune(app)["attachments"] == 1
    assert not _exists(app, old) and _exists(app, new)
    assert bob.get(f"/attachments/{new['id']}").status_code == 200
    alice.patch(f"/conversations/{gid}", {"avatar_attachment_id": None})
    assert _prune(app)["attachments"] == 1
    assert not _exists(app, new)


def test_an_avatar_used_by_either_a_user_or_a_conversation_is_kept(factory, alice, bob, app):
    user_only = upload(alice, PNG, "avatar", "image/png").json()
    group_only = upload(alice, PNG, "avatar", "image/png").json()
    alice.patch("/users/me", {"avatar_attachment_id": user_only["id"]})
    gid = factory.group(alice, [bob])
    alice.patch(f"/conversations/{gid}", {"avatar_attachment_id": group_only["id"]})
    _age(app, user_only["id"], group_only["id"], hours=24 * 30)
    assert _prune(app)["attachments"] == 0
    assert _exists(app, user_only) and _exists(app, group_only)


def test_avatar_collection_leaves_message_attachments_alone(factory, alice, bob, app):
    cid = factory.direct(alice, bob)
    att = upload(alice, b"cipher").json()
    alice.send(cid, "x", attachment_ids=[att["id"]])
    stale_avatar = upload(alice, PNG, "avatar", "image/png").json()
    _age(app, att["id"], stale_avatar["id"])
    assert _prune(app)["attachments"] == 1
    assert _exists(app, att) and not _exists(app, stale_avatar)

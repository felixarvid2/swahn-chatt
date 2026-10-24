import io
import json
import os
import re
import sqlite3
import tarfile
import uuid

import pytest
from fastapi.testclient import TestClient

from app.cli import main as cli_main
from app.main import create_app
from app.services.backup import BackupError, create_backup, restore_backup
from tests.conftest import make_settings

pytestmark = pytest.mark.sqlite_only  # backups are SQLite only (PostgreSQL: pg_dump)

NAME_RE = re.compile(r"^familychat-backup-\d{8}-\d{6}(-\d+)?\.tar\.gz$")


def populate(factory, alice, bob):
    cid = factory.direct(alice, bob)
    att_bytes = os.urandom(50_000)
    att = alice.post(
        "/attachments?purpose=message", content=att_bytes, headers={"Content-Type": "application/octet-stream"}
    ).json()
    sent = alice.send(cid, "with file", attachment_ids=[att["id"]]).json()["message"]
    alice.send(cid, "second")
    return cid, att, att_bytes, sent


def db_rows(path):
    con = sqlite3.connect(path)
    try:
        return {
            "users": con.execute("SELECT id, username, password_hash FROM users ORDER BY id").fetchall(),
            "messages": con.execute("SELECT id, seq, conversation_id FROM messages ORDER BY seq").fetchall(),
            "envelopes": con.execute(
                "SELECT message_id, device_id, body FROM message_envelopes ORDER BY message_id, device_id"
            ).fetchall(),
            "events": con.execute("SELECT seq, device_id, type FROM events ORDER BY seq").fetchall(),
            "keys": con.execute("SELECT device_id, identity_key FROM device_keys ORDER BY device_id").fetchall(),
        }
    finally:
        con.close()


def rewrite_archive(src, dst, mutate):
    """Copy a .tar.gz while letting `mutate(name, data) -> data | None (drop)` change members."""
    with tarfile.open(src) as tin, tarfile.open(dst, "w:gz") as tout:
        for m in tin:
            data = tin.extractfile(m).read()
            data = mutate(m.name, data)
            if data is None:
                continue
            info = tarfile.TarInfo(m.name)
            info.size = len(data)
            tout.addfile(info, io.BytesIO(data))


def test_backup_contents_and_manifest(factory, alice, bob, app, tmp_path):
    cid, att, att_bytes, _ = populate(factory, alice, bob)
    archive = create_backup(app.state.settings, tmp_path / "out")
    assert NAME_RE.match(archive.name) and archive.stat().st_mode & 0o077 == 0
    assert [p.name for p in (tmp_path / "out").iterdir()] == [archive.name]  # no temp leftovers
    with tarfile.open(archive) as tar:
        names = tar.getnames()
        manifest = json.load(tar.extractfile("manifest.json"))
    assert set(names) == {"manifest.json", "familychat.db", f"attachments/{att['id'][:2]}/{att['id']}"}
    assert manifest["counts"] == {
        "users": 2,
        "devices": 2,
        "conversations": 1,
        "messages": 2,
        "attachments": 1,
        "attachment_files": 1,
    }
    assert manifest["attachments"][0]["sha256"] == att["sha256"] and manifest["attachments"][0]["size"] == len(
        att_bytes
    )
    assert len(manifest["database"]["sha256"]) == 64


def test_backup_and_restore_roundtrip(factory, alice, bob, app, tmp_path, make_app):
    cid, att, att_bytes, sent = populate(factory, alice, bob)
    before = db_rows(app.state.settings.sqlite_path)
    assert before["envelopes"] and before["events"]
    archive = create_backup(app.state.settings, tmp_path / "backups")

    target = tmp_path / "restored"
    new_settings = make_settings(target)
    result = restore_backup(new_settings, archive)
    assert result["previous_data"] is None and result["counts"]["messages"] == 2
    # rows, ciphertext bytes and attachment bytes are identical
    assert db_rows(target / "familychat.db") == before
    restored_att = target / "attachments" / att["id"][:2] / att["id"]
    assert restored_att.read_bytes() == att_bytes
    assert [p for p in target.iterdir() if p.name.startswith(".restore-")] == []

    # the restored server is a working server: same logins, same queued events, same attachment
    app2 = create_app(new_settings)
    with TestClient(app2) as c2:
        r = c2.post("/api/v1/auth/refresh", json={"refresh_token": bob.refresh})
        assert r.status_code == 200
        hdr = {"Authorization": f"Bearer {r.json()['access_token']}"}
        events = c2.get("/api/v1/sync?after=0", headers=hdr).json()["events"]
        assert sent["id"] in [e["data"]["id"] for e in events if e["type"] == "message"]
        assert c2.get(f"/api/v1/attachments/{att['id']}", headers=hdr).content == att_bytes
        login = c2.post(
            "/api/v1/auth/login", json={"username": "alice", "password": alice.password, "device_name": "new"}
        )
        assert login.status_code == 200


def test_restore_refuses_to_overwrite_without_force(factory, alice, bob, app, tmp_path):
    populate(factory, alice, bob)
    archive = create_backup(app.state.settings, tmp_path / "b")
    settings = app.state.settings
    with pytest.raises(BackupError, match="--force"):
        restore_backup(settings, archive)
    assert alice.get("/users/me").status_code == 200  # live data untouched
    # also refuses when only attachments exist
    other = make_settings(tmp_path / "other")
    (other.attachments_dir / "ab").mkdir(parents=True)
    (other.attachments_dir / "ab" / "x").write_bytes(b"1")
    with pytest.raises(BackupError, match="--force"):
        restore_backup(other, archive)


def test_restore_with_force_keeps_previous_data_aside(factory, alice, bob, app, tmp_path):
    populate(factory, alice, bob)
    archive = create_backup(app.state.settings, tmp_path / "b")
    target = tmp_path / "target"
    s = make_settings(target)
    restore_backup(s, archive)
    con = sqlite3.connect(s.sqlite_path)
    con.execute("DELETE FROM messages")
    con.commit()
    con.close()
    result = restore_backup(s, archive, force=True)
    assert result["previous_data"] and (target / result["previous_data"].split("/")[-1] / "familychat.db").exists()
    assert len(db_rows(s.sqlite_path)["messages"]) == 2


def corrupt_cases(tmp_path, archive):
    raw = archive.read_bytes()
    out = {}
    p = tmp_path / "truncated.tar.gz"
    p.write_bytes(raw[: len(raw) // 2])
    out["truncated"] = p
    p = tmp_path / "bitflip.tar.gz"
    flipped = bytearray(raw)
    flipped[len(raw) // 2] ^= 0xFF
    p.write_bytes(bytes(flipped))
    out["bitflip"] = p
    p = tmp_path / "garbage.tar.gz"
    p.write_bytes(b"this is not an archive")
    out["garbage"] = p
    p = tmp_path / "tampered-db.tar.gz"
    rewrite_archive(archive, p, lambda n, d: d + b"x" if n == "familychat.db" else d)
    out["tampered db"] = p
    p = tmp_path / "tampered-att.tar.gz"
    rewrite_archive(archive, p, lambda n, d: b"evil" if n.startswith("attachments/") else d)
    out["tampered attachment"] = p
    p = tmp_path / "no-manifest.tar.gz"
    rewrite_archive(archive, p, lambda n, d: None if n == "manifest.json" else d)
    out["no manifest"] = p
    p = tmp_path / "missing-att.tar.gz"
    rewrite_archive(archive, p, lambda n, d: None if n.startswith("attachments/") else d)
    out["missing attachment"] = p
    p = tmp_path / "extra.tar.gz"
    with tarfile.open(archive) as tin, tarfile.open(p, "w:gz") as tout:
        for m in tin:
            tout.addfile(m, tin.extractfile(m))
        info = tarfile.TarInfo(f"attachments/ab/{uuid.uuid4()}")
        info.size = 3
        tout.addfile(info, io.BytesIO(b"abc"))
    out["extra file"] = p
    p = tmp_path / "traversal.tar.gz"
    with tarfile.open(archive) as tin, tarfile.open(p, "w:gz") as tout:
        for m in tin:
            tout.addfile(m, tin.extractfile(m))
        info = tarfile.TarInfo("../../escape")
        info.size = 3
        tout.addfile(info, io.BytesIO(b"abc"))
    out["path traversal"] = p
    return out


def test_corrupted_archives_are_rejected_and_change_nothing(factory, alice, bob, app, tmp_path):
    populate(factory, alice, bob)
    archive = create_backup(app.state.settings, tmp_path / "b")
    for label, bad in corrupt_cases(tmp_path, archive).items():
        target = tmp_path / f"t-{label.replace(' ', '-')}"
        with pytest.raises(BackupError):
            restore_backup(make_settings(target), bad)
        assert not (target / "familychat.db").exists(), label
        assert not (target / "attachments").exists() or not any((target / "attachments").iterdir()), label
        assert not (tmp_path / "escape").exists()
    # and a forced restore of a bad archive leaves existing data alone
    live = app.state.settings
    with pytest.raises(BackupError):
        restore_backup(live, tmp_path / "tampered-db.tar.gz", force=True)
    assert alice.get("/users/me").status_code == 200


def test_backup_is_consistent_while_writing(factory, alice, bob, app, tmp_path):
    import threading

    cid = factory.direct(alice, bob)
    stop = threading.Event()

    def writer():
        while not stop.is_set():
            alice.send(cid, "w")

    t = threading.Thread(target=writer)
    t.start()
    try:
        archive = create_backup(app.state.settings, tmp_path / "b")
    finally:
        stop.set()
        t.join()
    target = tmp_path / "t"
    restore_backup(make_settings(target), archive)
    con = sqlite3.connect(target / "familychat.db")
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    msgs = con.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
    envs = con.execute("SELECT COUNT(*) FROM message_envelopes").fetchone()[0]
    assert msgs == envs  # one envelope per message (bob only): no torn writes
    con.close()


def test_backup_cli_prints_only_the_archive_path_last(factory, alice, bob, app, tmp_path, monkeypatch, capsys):
    populate(factory, alice, bob)
    s = app.state.settings
    monkeypatch.setenv("FAMILYCHAT_ENV", "development")
    monkeypatch.setenv("FAMILYCHAT_DATA_DIR", str(s.data_dir))
    assert cli_main(["backup", str(tmp_path / "cli-out")]) == 0
    last = capsys.readouterr().out.strip().splitlines()[-1]
    assert NAME_RE.match(os.path.basename(last)) and os.path.isfile(last)

    target = tmp_path / "cli-restore"
    monkeypatch.setenv("FAMILYCHAT_DATA_DIR", str(target))
    assert cli_main(["restore", last]) == 0
    assert db_rows(target / "familychat.db")["users"]
    assert cli_main(["restore", last]) == 1  # existing data, no --force
    assert "--force" in capsys.readouterr().err
    assert cli_main(["restore", last, "--force"]) == 0
    assert cli_main(["restore", str(tmp_path / "missing.tar.gz"), "--force"]) == 1

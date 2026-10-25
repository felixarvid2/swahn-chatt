"""Compatibility with data and settings from before the rename to Swahn Chat (FamilyChat)."""

import io
import json
import logging
import sqlite3
import tarfile

import pytest

from app.cli import main as cli_main
from app.config import ConfigError, Settings
from app.legacy import adopt_legacy_database
from app.services.backup import create_backup, restore_backup, verify_backup
from tests.conftest import SECRET, make_settings
from tests.test_backup import populate


def _sqlite_file(path, value):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE t (v TEXT)")
    con.execute("INSERT INTO t VALUES (?)", (value,))
    con.commit()
    con.close()


def test_old_database_file_is_renamed_with_its_wal_files(tmp_path, caplog):
    _sqlite_file(tmp_path / "familychat.db", "old")
    (tmp_path / "familychat.db-wal").write_bytes(b"wal")
    (tmp_path / "familychat.db-shm").write_bytes(b"shm")
    settings = Settings(env="development", data_dir=tmp_path, secret_key=SECRET)
    with caplog.at_level(logging.INFO, logger="swahnchat.legacy"):
        assert adopt_legacy_database(settings) is True
    assert sorted(p.name for p in tmp_path.iterdir()) == ["swahnchat.db", "swahnchat.db-shm", "swahnchat.db-wal"]
    assert (tmp_path / "swahnchat.db-wal").read_bytes() == b"wal"
    con = sqlite3.connect(tmp_path / "swahnchat.db")
    assert con.execute("SELECT v FROM t").fetchone() == ("old",)
    con.close()
    assert len([r for r in caplog.records if r.name == "swahnchat.legacy"]) == 1
    assert adopt_legacy_database(settings) is False  # second start: nothing left to do


def test_existing_new_database_is_never_overwritten(tmp_path):
    _sqlite_file(tmp_path / "familychat.db", "old")
    _sqlite_file(tmp_path / "swahnchat.db", "new")
    settings = Settings(env="development", data_dir=tmp_path, secret_key=SECRET)
    assert adopt_legacy_database(settings) is False
    assert (tmp_path / "familychat.db").exists()
    con = sqlite3.connect(tmp_path / "swahnchat.db")
    assert con.execute("SELECT v FROM t").fetchone() == ("new",)
    con.close()


def test_nothing_happens_without_an_old_file(tmp_path):
    assert adopt_legacy_database(Settings(env="development", data_dir=tmp_path, secret_key=SECRET)) is False
    assert list(tmp_path.iterdir()) == []


def test_old_variables_are_logged_and_not_read(tmp_path, monkeypatch, caplog):
    monkeypatch.delenv("SWAHNCHAT_SECRET_KEY", raising=False)
    monkeypatch.setenv("FAMILYCHAT_MAX_ATTACHMENT_MB", "7")
    with caplog.at_level(logging.ERROR, logger="swahnchat.config"):
        s = Settings(env="development", data_dir=tmp_path, secret_key=SECRET)
    assert s.max_attachment_mb == 100  # the old value is not used
    assert any(
        "FAMILYCHAT_MAX_ATTACHMENT_MB" in r.getMessage() and "SWAHNCHAT_" in r.getMessage() for r in caplog.records
    )
    assert "7" not in "".join(r.getMessage() for r in caplog.records)


def test_production_fails_when_only_the_old_secret_key_is_set(tmp_path, monkeypatch):
    monkeypatch.delenv("SWAHNCHAT_SECRET_KEY", raising=False)
    monkeypatch.setenv("FAMILYCHAT_SECRET_KEY", "x" * 48)
    with pytest.raises(ConfigError, match="FAMILYCHAT_SECRET_KEY") as exc:
        Settings(env="production", data_dir=tmp_path)
    assert "x" * 48 not in str(exc.value)
    # development still starts (ephemeral key), but logs the error
    assert Settings(env="development", data_dir=tmp_path).env == "development"


def test_check_config_reports_old_variables_as_an_error(tmp_path, monkeypatch, capsys):
    for name in list(__import__("os").environ):
        if name.startswith(("SWAHNCHAT_", "FAMILYCHAT_")):
            monkeypatch.delenv(name)
    monkeypatch.setenv("SWAHNCHAT_DATA_DIR", str(tmp_path / "d"))
    monkeypatch.setenv("SWAHNCHAT_ENV", "development")
    monkeypatch.setenv("FAMILYCHAT_DOMAIN", "hemma.example.org")
    assert cli_main(["check-config"]) == 1
    assert "ERROR: the old FAMILYCHAT_* variables are set (FAMILYCHAT_DOMAIN)" in capsys.readouterr().out


def _as_old_archive(src, dst):
    """Rewrite a new archive the way a pre-rename server made it: database named familychat.db."""
    with tarfile.open(src) as tin, tarfile.open(dst, "w:gz") as tout:
        for m in tin:
            data = tin.extractfile(m).read()
            name = m.name
            if name == "swahnchat.db":
                name = "familychat.db"
            if name == "manifest.json":
                manifest = json.loads(data)
                manifest["database"]["path"] = "familychat.db"
                data = json.dumps(manifest).encode()
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tout.addfile(info, io.BytesIO(data))


def test_archives_made_before_the_rename_verify_and_restore(factory, alice, bob, app, tmp_path):
    populate(factory, alice, bob)
    new = create_backup(app.state.settings, tmp_path / "b")
    assert new.name.startswith("swahnchat-backup-")
    old = tmp_path / "familychat-backup-20260101-031500.tar.gz"
    _as_old_archive(new, old)
    assert verify_backup(old)["counts"] == verify_backup(new)["counts"]
    target = tmp_path / "restored"
    restore_backup(make_settings(target), old)
    assert (target / "swahnchat.db").is_file() and not (target / "familychat.db").exists()

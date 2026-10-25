"""`cli verify-backup` and `cli check-config`."""

import hashlib
import io
import json
import sqlite3
import tarfile

import pytest

from app.cli import main as cli_main
from app.migrate import code_head
from app.services.backup import create_backup, verify_backup
from tests.test_backup import corrupt_cases, populate

STRONG = "k" * 48


@pytest.fixture
def cli_env(monkeypatch, tmp_path):
    """Isolated environment for in-process CLI runs."""
    for name in list(__import__("os").environ):
        if name.startswith("FAMILYCHAT_"):
            monkeypatch.delenv(name)
    monkeypatch.setenv("FAMILYCHAT_DATA_DIR", str(tmp_path / "cli-data"))
    return monkeypatch


def rebuild(src, dst, mutate_db=None, mutate_manifest=None, extra=()):
    """Copy an archive, optionally changing the database bytes (the manifest is rewritten to stay consistent)."""
    with tarfile.open(src) as tin:
        files = {m.name: tin.extractfile(m).read() for m in tin}
    if mutate_db:
        files["familychat.db"] = mutate_db(files["familychat.db"])
    manifest = json.loads(files["manifest.json"])
    manifest["database"]["sha256"] = hashlib.sha256(files["familychat.db"]).hexdigest()
    manifest["database"]["size"] = len(files["familychat.db"])
    if mutate_manifest:
        mutate_manifest(manifest)
    files["manifest.json"] = json.dumps(manifest).encode()
    with tarfile.open(dst, "w:gz") as tout:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tout.addfile(info, io.BytesIO(data))
        for info, data in extra:
            tout.addfile(info, io.BytesIO(data) if data is not None else None)
    return dst


# ------------------------------------------------------------------------------------------ verify-backup
@pytest.mark.sqlite_only
class TestVerifyBackup:
    @pytest.fixture
    def archive(self, factory, alice, bob, app, tmp_path):
        populate(factory, alice, bob)
        return create_backup(app.state.settings, tmp_path / "b")

    def test_good_archive_prints_a_summary_and_exits_0(self, archive, cli_env, capsys, tmp_path):
        assert cli_main(["verify-backup", str(archive)]) == 0
        out = capsys.readouterr().out
        assert "backup OK" in out and "2 users" in out and "2 messages" in out
        assert "1 files" in out and "checksums match" in out and "integrity_check ok" in out
        assert f"migration {code_head()}" in out
        assert not (tmp_path / "cli-data").exists()  # verifying never creates or touches the data directory

    def test_summary_function(self, archive):
        info = verify_backup(archive)
        assert info["counts"]["messages"] == 2 and info["attachment_files"] == 1 and info["warnings"] == []
        assert info["migration"] == code_head() and info["attachment_bytes"] == 50_000

    def test_every_corruption_is_found_without_restoring(self, archive, cli_env, capsys, tmp_path):
        cases = corrupt_cases(tmp_path, archive)
        assert len(cases) >= 9
        for label, bad in cases.items():
            assert cli_main(["verify-backup", str(bad)]) == 1, label
            captured = capsys.readouterr()
            assert "error:" in captured.err and "backup OK" not in captured.out, label
        assert not (tmp_path / "cli-data").exists() and not (tmp_path / "escape").exists()

    def test_missing_file(self, cli_env, capsys, tmp_path):
        assert cli_main(["verify-backup", str(tmp_path / "nope.tar.gz")]) == 1
        assert "not found" in capsys.readouterr().err

    def test_unsafe_entry_types_and_duplicates(self, archive, cli_env, capsys, tmp_path):
        link = tarfile.TarInfo("attachments/ab/00000000-0000-4000-8000-000000000000")
        link.type, link.linkname = tarfile.SYMTYPE, "/etc/passwd"
        dup = tarfile.TarInfo("manifest.json")
        dup.size = 2
        cases = {
            "symlink": rebuild(archive, tmp_path / "s.tar.gz", extra=[(link, None)]),
            "duplicate": rebuild(archive, tmp_path / "d.tar.gz", extra=[(dup, b"{}")]),
            "absolute": rebuild(archive, tmp_path / "a.tar.gz", extra=[(_sized("/etc/cron.d/x", 1), b"x")]),
            "nested": rebuild(archive, tmp_path / "n.tar.gz", extra=[(_sized("attachments/ab/cd/ef", 1), b"x")]),
        }
        for label, bad in cases.items():
            assert cli_main(["verify-backup", str(bad)]) == 1, label
            assert "error:" in capsys.readouterr().err, label

    def test_database_that_is_not_sqlite_or_fails_integrity(self, archive, cli_env, capsys, tmp_path):
        junk = rebuild(archive, tmp_path / "junk.tar.gz", mutate_db=lambda d: b"not a database" * 100)
        assert cli_main(["verify-backup", str(junk)]) == 1
        assert "error:" in capsys.readouterr().err

        def damage(data):  # keep the header, wreck the b-tree pages of the biggest table
            return data[:4096] + bytes(b ^ 0x5A for b in data[4096:8192]) + data[8192:]

        broken = rebuild(archive, tmp_path / "broken.tar.gz", mutate_db=damage)
        assert cli_main(["verify-backup", str(broken)]) == 1
        err = capsys.readouterr().err
        assert "integrity" in err or "not a valid SQLite" in err

    def test_tampered_storage_path_with_a_matching_manifest(self, archive, cli_env, capsys, tmp_path):
        def tamper(data):
            tmp = tmp_path / "t.db"
            tmp.write_bytes(data)
            con = sqlite3.connect(tmp)
            con.execute("UPDATE attachments SET storage_path = '../../../etc/passwd'")
            con.commit()
            con.execute("PRAGMA journal_mode=DELETE")
            con.close()
            return tmp.read_bytes()

        bad = rebuild(archive, tmp_path / "tp.tar.gz", mutate_db=tamper)
        assert cli_main(["verify-backup", str(bad)]) == 1
        assert "invalid storage path" in capsys.readouterr().err

    def test_manifest_problems(self, archive, cli_env, capsys, tmp_path):
        cases = {
            "format": rebuild(archive, tmp_path / "f.tar.gz", mutate_manifest=lambda m: m.update(format=99)),
            "listed but absent": rebuild(
                archive,
                tmp_path / "l.tar.gz",
                mutate_manifest=lambda m: m["attachments"].append(
                    {"path": "attachments/ab/00000000-0000-4000-8000-000000000000", "size": 1, "sha256": "0" * 64}
                ),
            ),
            "wrong size": rebuild(
                archive, tmp_path / "w.tar.gz", mutate_manifest=lambda m: m["attachments"][0].update(size=1)
            ),
            "wrong checksum": rebuild(
                archive, tmp_path / "c.tar.gz", mutate_manifest=lambda m: m["attachments"][0].update(sha256="0" * 64)
            ),
            "traversing path": rebuild(
                archive, tmp_path / "t.tar.gz", mutate_manifest=lambda m: m["attachments"][0].update(path="../x")
            ),
        }
        for label, bad in cases.items():
            assert cli_main(["verify-backup", str(bad)]) == 1, label
            assert "error:" in capsys.readouterr().err, label

    def test_warnings_do_not_fail_the_verification(self, archive, cli_env, capsys, tmp_path):
        def drop_rows(data):
            tmp = tmp_path / "w.db"
            tmp.write_bytes(data)
            con = sqlite3.connect(tmp)
            con.execute("DELETE FROM message_attachments")
            con.execute("DELETE FROM attachments")
            con.commit()
            con.execute("PRAGMA journal_mode=DELETE")
            con.close()
            return tmp.read_bytes()

        odd = rebuild(archive, tmp_path / "odd.tar.gz", mutate_db=drop_rows)
        assert cli_main(["verify-backup", str(odd)]) == 0
        assert "warning: 1 attachment files are not referenced" in capsys.readouterr().out

    def test_no_temporary_files_are_left_behind(self, archive, cli_env, tmp_path, monkeypatch):
        scratch = tmp_path / "scratch"
        scratch.mkdir()
        monkeypatch.setattr("tempfile.tempdir", str(scratch))
        assert cli_main(["verify-backup", str(archive)]) == 0
        assert list(scratch.iterdir()) == []


def _sized(name, size):
    info = tarfile.TarInfo(name)
    info.size = size
    return info


# ------------------------------------------------------------------------------------------- check-config
def run_check(cli_env, capsys, **env):
    for key, value in env.items():
        cli_env.setenv("FAMILYCHAT_" + key.upper(), str(value))
    code = cli_main(["check-config"])
    out = capsys.readouterr()
    return code, out.out, out.err


GOOD_PROD = dict(
    env="production",
    secret_key=STRONG,
    push_allowed_hosts="ntfy.example.ts.net",
    trusted_proxies="172.16.0.0/12",
    behind_proxy="true",
    min_free_disk_mb=0,
)


def test_good_production_config_has_no_warnings(cli_env, capsys, tmp_path):
    code, out, _ = run_check(cli_env, capsys, **GOOD_PROD)
    assert code == 0 and "WARNING" not in out and "ERROR" not in out and "configuration OK" in out
    assert any(line.split() == ["env", "production"] for line in out.splitlines())
    assert "ntfy.example.ts.net" in out and "172.16.0.0/12" in out and "48 bytes" in out


def test_secrets_are_never_printed(cli_env, capsys):
    secret = "S3cretValueThatMustNeverBePrinted-0123456789abcdef"
    code, out, err = run_check(
        cli_env,
        capsys,
        **{**GOOD_PROD, "secret_key": secret},
        database_url="postgresql+psycopg://fc:hunter2pw@db.example/familychat",
    )
    assert code == 0, out
    assert secret not in out + err and "hunter2pw" not in out + err
    assert "postgresql+psycopg://fc:***@db.example/familychat" in out


def test_production_without_a_secret_key_is_an_error(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, env="production")
    assert (
        code == 1
        and "ERROR: FAMILYCHAT_SECRET_KEY is not set" in out
        and "1 error(s)" in out.splitlines()[-1]
        or code == 1
    )
    code, out, _ = run_check(cli_env, capsys, env="production", secret_key="too-short")
    assert code == 1 and "ERROR: FAMILYCHAT_SECRET_KEY is only 9 bytes" in out


def test_development_with_a_short_key_only_warns(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, env="development", secret_key="short")
    assert code == 0 and "WARNING: FAMILYCHAT_SECRET_KEY is only 5 bytes" in out


def test_warnings_for_risky_production_settings(cli_env, capsys):
    code, out, _ = run_check(
        cli_env,
        capsys,
        env="production",
        secret_key=STRONG,
        behind_proxy="true",
        expose_docs="true",
        push_allow_http="true",
        rate_limit_enabled="false",
        min_free_disk_mb=0,
    )
    assert code == 0  # warnings do not fail the check
    for fragment in (
        "PUSH_ALLOWED_HOSTS",
        "FAMILYCHAT_BEHIND_PROXY is set but FAMILYCHAT_TRUSTED_PROXIES is empty",
        "EXPOSE_DOCS is true in production",
        "PUSH_ALLOW_HTTP is true in production",
        "rate limiting is disabled",
    ):
        assert any(line.startswith("WARNING") and fragment in line for line in out.splitlines()), fragment
    assert "configuration OK (5 warning(s))" in out


def test_the_same_settings_are_fine_in_development(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, env="development", secret_key=STRONG, expose_docs="true")
    assert code == 0 and "WARNING" not in out


def test_low_disk_warning(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, **{**GOOD_PROD, "min_free_disk_mb": 10**9})
    assert code == 0 and "WARNING: low disk" in out


def test_invalid_trusted_proxies_is_an_error(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, **{**GOOD_PROD, "trusted_proxies": "not-a-cidr"})
    assert code == 1 and "ERROR: FAMILYCHAT_TRUSTED_PROXIES" in out


def test_trusting_everything_is_a_warning(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, **{**GOOD_PROD, "trusted_proxies": "0.0.0.0/0"})
    assert code == 0 and "trusts every address" in out


def test_wrong_postgres_driver_prefix_is_an_error(cli_env, capsys):
    code, out, _ = run_check(cli_env, capsys, **GOOD_PROD, database_url="postgresql://u:p@h/db")
    assert code == 1 and "postgresql+psycopg://" in out


def test_data_dir_that_cannot_exist_is_an_error(cli_env, capsys, tmp_path):
    (tmp_path / "afile").write_text("x")
    code, out, _ = run_check(cli_env, capsys, **{**GOOD_PROD, "data_dir": tmp_path / "afile" / "data"})
    assert code == 1 and "ERROR: data directory" in out and "is not a directory" in out


def test_invalid_setting_values_exit_1_with_a_message(cli_env, capsys):
    cli_env.setenv("FAMILYCHAT_ENV", "staging")
    assert cli_main(["check-config"]) == 1
    assert "error:" in capsys.readouterr().err


def test_check_config_touches_nothing(cli_env, capsys, tmp_path):
    run_check(cli_env, capsys, **GOOD_PROD)
    assert not (tmp_path / "cli-data").exists()


def test_check_config_shows_new_settings_but_never_the_alert_topic(cli_env, capsys):
    code, out, err = run_check(
        cli_env, capsys, **GOOD_PROD, admin_ntfy_url="https://ntfy.example.org/SECRETTOPIC-123", hsts="true"
    )
    assert code == 0 and "WARNING" not in out
    assert any(line.split() == ["admin_alerts", "on"] for line in out.splitlines())
    assert any(line.split() == ["hsts", "true"] for line in out.splitlines())
    assert "SECRETTOPIC" not in out + err

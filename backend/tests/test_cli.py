import os
import re
import subprocess
import sys
from pathlib import Path

from fastapi.testclient import TestClient

from app.cli import main as cli_main
from app.main import create_app
from app.migrate import upgrade
from tests.conftest import PASSWORD, SECRET, Factory, make_settings

BACKEND = Path(__file__).resolve().parent.parent
CODE_RE = re.compile(r"^[A-Z2-9]{5}(-[A-Z2-9]{5}){3}$")


def run_cli(data_dir, *args, env_name="development", extra_env=None):
    env = {
        **os.environ,
        "FAMILYCHAT_ENV": env_name,
        "FAMILYCHAT_DATA_DIR": str(data_dir),
        "FAMILYCHAT_ARGON2_MEMORY_KB": "8",
        "FAMILYCHAT_ARGON2_TIME_COST": "1",
        "FAMILYCHAT_ARGON2_PARALLELISM": "1",
    }
    env.update(extra_env or {})
    env.pop("FAMILYCHAT_SECRET_KEY", None)
    return subprocess.run(
        [sys.executable, "-m", "app.cli", *args], cwd=BACKEND, env=env, capture_output=True, text=True, timeout=120
    )


def serve(data_dir):
    settings = make_settings(data_dir)
    upgrade(settings)
    app = create_app(settings)
    return app, TestClient(app)


def test_create_invite_prints_only_code_on_last_line_and_works_on_fresh_dir(tmp_path):
    data = tmp_path / "fresh"  # nothing exists yet: the CLI creates and migrates
    r = run_cli(data, "create-invite", "--admin", "--note", "first admin")
    assert r.returncode == 0, r.stderr
    lines = r.stdout.strip().splitlines()
    assert lines and CODE_RE.match(lines[-1]) and len(lines) == 1  # stdout carries nothing but the code
    assert (data / "familychat.db").exists()
    app, client = serve(data)
    with client:
        res = client.post(
            "/api/v1/auth/invite/redeem",
            json={
                "code": lines[-1],
                "username": "root",
                "password": PASSWORD,
                "display_name": "Root",
                "device_name": "p",
            },
        )
        assert res.status_code == 201 and res.json()["user"]["is_admin"] is True
        hdr = {"Authorization": f"Bearer {res.json()['access_token']}"}
        inv = client.get("/api/v1/admin/invitations", headers=hdr).json()
        assert inv[0]["note"] == "first admin" and inv[0]["used_by"] == res.json()["user"]["id"]


def test_create_invite_member_hours_and_validation(tmp_path):
    r = run_cli(tmp_path, "create-invite", "--hours", "1")
    code = r.stdout.strip().splitlines()[-1]
    app, client = serve(tmp_path)
    with client:
        f = Factory(app, client)
        admin = f.user("boss", admin=True)
        (inv,) = [i for i in admin.get("/admin/invitations").json() if i["used_at"] is None and not i["is_admin"]]
        assert 0 < inv["expires_at"] - inv["created_at"] <= 3_600_000
        res = client.post(
            "/api/v1/auth/invite/redeem",
            json={"code": code, "username": "kid", "password": PASSWORD, "display_name": "Kid", "device_name": "p"},
        )
        assert res.json()["user"]["is_admin"] is False
    bad = run_cli(tmp_path, "create-invite", "--hours", "9999")
    assert bad.returncode == 2 and bad.stdout.strip() == ""


def test_cli_works_in_production_without_a_secret_key(tmp_path):
    r = run_cli(tmp_path, "create-invite", env_name="production")
    assert r.returncode == 0, r.stderr
    assert CODE_RE.match(r.stdout.strip().splitlines()[-1])


def test_list_and_remove_user(tmp_path):
    app, client = serve(tmp_path)
    with client:
        f = Factory(app, client)
        admin, bob = f.user("boss", admin=True), f.user("bob")
        listing = run_cli(tmp_path, "list-users").stdout
        assert "boss" in listing and "admin" in listing and "bob" in listing
        refuse = run_cli(tmp_path, "remove-user", "boss")
        assert refuse.returncode == 1 and "only administrator" in refuse.stderr
        assert run_cli(tmp_path, "remove-user", "nobody").returncode == 1
        assert bob.get("/users/me").status_code == 200
        ok = run_cli(tmp_path, "remove-user", "Bob")
        assert ok.returncode == 0, ok.stderr
        assert bob.get("/users/me").status_code == 401  # a running server notices at once
        assert "removed" in run_cli(tmp_path, "list-users").stdout
        assert admin.get("/users/me").status_code == 200


def test_seed_dev_refuses_outside_development(tmp_path):
    r = run_cli(tmp_path, "seed-dev", env_name="production")
    assert r.returncode == 1 and "development" in r.stderr
    assert not (tmp_path / "familychat.db").exists()


def test_seed_dev_creates_users_conversations_and_is_idempotent(tmp_path):
    first = run_cli(tmp_path, "seed-dev", "--password", PASSWORD)
    assert first.returncode == 0, first.stderr
    assert "end-to-end encrypted" in first.stderr  # says why there are no messages
    second = run_cli(tmp_path, "seed-dev", "--password", PASSWORD)
    assert second.returncode == 0 and "created" not in second.stdout.replace("exists", "")
    app, client = serve(tmp_path)
    with client:
        res = client.post("/api/v1/auth/login", json={"username": "alice", "password": PASSWORD, "device_name": "dev"})
        assert res.status_code == 200 and res.json()["user"]["is_admin"] is True
        hdr = {"Authorization": f"Bearer {res.json()['access_token']}"}
        convs = client.get("/api/v1/conversations", headers=hdr).json()
        assert sorted((c["type"], c["name"], len(c["members"])) for c in convs) == [
            ("direct", None, 2),
            ("group", "Familjen", 3),
        ]
        assert len(client.get("/api/v1/users", headers=hdr).json()) == 3
    # generated passwords are printed when --password is absent
    other = tmp_path / "other"
    out = run_cli(other, "seed-dev").stdout
    pw = re.search(r"created alice: password (\S+)", out).group(1)
    assert len(pw) >= 10
    app2, client2 = serve(other)
    with client2:
        assert (
            client2.post(
                "/api/v1/auth/login", json={"username": "alice", "password": pw, "device_name": "d"}
            ).status_code
            == 200
        )


def test_cli_main_in_process_invite(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("FAMILYCHAT_ENV", "development")
    monkeypatch.setenv("FAMILYCHAT_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("FAMILYCHAT_SECRET_KEY", SECRET)
    assert cli_main(["create-invite"]) == 0
    assert CODE_RE.match(capsys.readouterr().out.strip().splitlines()[-1])

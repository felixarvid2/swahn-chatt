"""Test fixtures: every test gets its own temp data dir, migrated SQLite database and app instance."""

import base64
import os
import shutil
import uuid
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.migrate import upgrade
from app.routers.admin import create_invitation

SECRET = "test-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz"
PASSWORD = "correct horse battery"


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode()


def make_settings(data_dir: Path, **overrides) -> Settings:
    values = dict(
        env="development",
        secret_key=SECRET,
        data_dir=data_dir,
        argon2_time_cost=1,
        argon2_memory_kb=8,
        argon2_parallelism=1,
        rate_limit_enabled=False,
        background_tasks=False,
    )
    values.update(overrides)
    return Settings(**values)


@pytest.fixture(scope="session")
def template_db(tmp_path_factory) -> Path:
    """A freshly migrated database, built once through the real Alembic migration."""
    d = tmp_path_factory.mktemp("template")
    upgrade(make_settings(d))
    return d / "familychat.db"


@pytest.fixture
def make_app(tmp_path, template_db):
    created = []

    def factory(**overrides) -> tuple:
        data = tmp_path / f"data{len(created)}"
        data.mkdir()
        shutil.copy(template_db, data / "familychat.db")
        settings = make_settings(data, **overrides)
        pushes: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            pushes.append(request)
            return httpx.Response(201)

        app = create_app(settings, push_transport=httpx.MockTransport(handler))
        app.state.pushes = pushes
        client = TestClient(app, base_url="http://testserver")
        client.__enter__()
        created.append(client)
        return app, client

    yield factory
    for c in created:
        c.__exit__(None, None, None)


@pytest.fixture
def app_client(make_app):
    return make_app()


@pytest.fixture
def app(app_client):
    return app_client[0]


@pytest.fixture
def client(app_client):
    return app_client[1]


@dataclass
class Actor:
    client: TestClient
    app: object
    username: str
    user: dict
    device: dict
    access: str
    refresh: str
    password: str = PASSWORD
    keyed: bool = False
    extra: dict = field(default_factory=dict)

    @property
    def id(self) -> str:
        return self.user["id"]

    @property
    def device_id(self) -> str:
        return self.device["id"]

    @property
    def headers(self) -> dict:
        return {"Authorization": f"Bearer {self.access}"}

    def req(self, method: str, path: str, **kw):
        headers = {**self.headers, **kw.pop("headers", {})}
        return self.client.request(method, "/api/v1" + path, headers=headers, **kw)

    def get(self, path, **kw):
        return self.req("GET", path, **kw)

    def post(self, path, json=None, **kw):
        return self.req("POST", path, json=json, **kw)

    def put(self, path, json=None, **kw):
        return self.req("PUT", path, json=json, **kw)

    def patch(self, path, json=None, **kw):
        return self.req("PATCH", path, json=json, **kw)

    def delete(self, path, **kw):
        return self.req("DELETE", path, **kw)

    # ---- keys
    def upload_keys(self, one_time: int = 5) -> dict:
        key_id = 1
        body = {
            "registration_id": 1234,
            "identity_key": b64(b"\x05" + os.urandom(32)),
            "signed_prekey": {"key_id": 1, "public_key": b64(os.urandom(33)), "signature": b64(os.urandom(64))},
            "pq_last_resort_prekey": {
                "key_id": 1,
                "public_key": b64(os.urandom(64)),
                "signature": b64(os.urandom(64)),
            },
            "one_time_prekeys": [{"key_id": key_id + i, "public_key": b64(os.urandom(33))} for i in range(one_time)],
            "one_time_pq_prekeys": [
                {"key_id": key_id + i, "public_key": b64(os.urandom(64)), "signature": b64(os.urandom(64))}
                for i in range(one_time)
            ],
        }
        r = self.put("/keys", body)
        assert r.status_code == 204, r.text
        self.keyed = True
        self.extra["keys"] = body
        return body

    # ---- messaging
    def member_devices(self, conversation_id: str) -> list[dict]:
        conv = self.get(f"/conversations/{conversation_id}").json()
        out = []
        for m in conv["members"]:
            for d in self.get(f"/users/{m['user_id']}/devices").json():
                if d["identity_key"] and d["id"] != self.device_id:
                    out.append(d)
        return out

    def envelopes(self, conversation_id: str, text: str) -> list[dict]:
        return [
            {"device_id": d["id"], "type": "whisper", "body": b64(f"ct|{text}|{d['id']}".encode())}
            for d in self.member_devices(conversation_id)
        ]

    def send(self, conversation_id: str, text: str = "hi", message_id: str | None = None, **extra):
        body = {
            "id": message_id or str(uuid.uuid4()),
            "conversation_id": conversation_id,
            "kind": "message",
            "client_ts": 1700000000000,
            "attachment_ids": [],
            "envelopes": self.envelopes(conversation_id, text),
        }
        body.update(extra)
        return self.post("/messages", body)

    def sync(self, after: int = 0, limit: int = 200) -> dict:
        r = self.get(f"/sync?after={after}&limit={limit}")
        assert r.status_code == 200, r.text
        return r.json()

    def ack(self, cursor: int):
        r = self.post("/sync/ack", {"cursor": cursor})
        assert r.status_code == 204, r.text

    def events(self, type_: str | None = None, after: int = 0) -> list[dict]:
        evs = self.sync(after)["events"]
        return [e for e in evs if type_ is None or e["type"] == type_]


class Factory:
    def __init__(self, app, client):
        self.app = app
        self.client = client
        self.n = 0

    def invite(self, admin: bool = False) -> str:
        with self.app.state.session_factory() as db:
            _inv, code = create_invitation(db, None, "test", 72, admin)
            db.commit()
        return code

    def user(self, name: str | None = None, admin: bool = False, keys: bool = True, password: str = PASSWORD) -> Actor:
        self.n += 1
        name = name or f"user{self.n}"
        r = self.client.post(
            "/api/v1/auth/invite/redeem",
            json={
                "code": self.invite(admin),
                "username": name,
                "password": password,
                "display_name": name.title(),
                "device_name": "Test phone",
            },
        )
        assert r.status_code == 201, r.text
        d = r.json()
        actor = Actor(
            self.client, self.app, name, d["user"], d["device"], d["access_token"], d["refresh_token"], password
        )
        if keys:
            actor.upload_keys()
        return actor

    def login(self, base: Actor, device_name: str = "Second phone", keys: bool = True) -> Actor:
        r = self.client.post(
            "/api/v1/auth/login",
            json={"username": base.username, "password": base.password, "device_name": device_name},
        )
        assert r.status_code == 200, r.text
        d = r.json()
        actor = Actor(
            self.client,
            self.app,
            base.username,
            d["user"],
            d["device"],
            d["access_token"],
            d["refresh_token"],
            base.password,
        )
        if keys:
            actor.upload_keys()
        return actor

    def direct(self, a: Actor, b: Actor) -> str:
        r = a.post("/conversations", {"type": "direct", "member_user_ids": [b.id]})
        assert r.status_code in (200, 201), r.text
        return r.json()["id"]

    def group(self, creator: Actor, others: list[Actor], name: str = "Familjen") -> str:
        r = creator.post("/conversations", {"type": "group", "name": name, "member_user_ids": [o.id for o in others]})
        assert r.status_code == 201, r.text
        return r.json()["id"]


@pytest.fixture
def factory(app, client) -> Factory:
    return Factory(app, client)


@pytest.fixture
def admin(factory) -> "Actor":
    return factory.user("admin", admin=True)


@pytest.fixture
def alice(factory):
    return factory.user("alice")


@pytest.fixture
def bob(factory):
    return factory.user("bob")


@pytest.fixture
def carol(factory):
    return factory.user("carol")

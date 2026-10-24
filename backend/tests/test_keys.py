import os
import uuid

from tests.conftest import b64


def test_first_upload_requires_identity_material(factory):
    a = factory.user("alice", keys=False)
    assert a.put("/keys", {"one_time_prekeys": [{"key_id": 1, "public_key": b64(b"x" * 33)}]}).status_code == 422
    assert a.put("/keys", {"registration_id": 1, "identity_key": b64(b"i" * 33)}).status_code == 422
    assert a.get("/keys/status").json() == {
        "one_time_prekeys": 0,
        "one_time_pq_prekeys": 0,
        "signed_prekey_id": None,
        "pq_last_resort_prekey_id": None,
    }


def test_upload_status_and_topup(factory):
    a = factory.user("alice", keys=False)
    a.upload_keys(one_time=5)
    assert a.get("/keys/status").json() == {
        "one_time_prekeys": 5,
        "one_time_pq_prekeys": 5,
        "signed_prekey_id": 1,
        "pq_last_resort_prekey_id": 1,
    }
    more = {
        "one_time_prekeys": [{"key_id": i, "public_key": b64(os.urandom(33))} for i in range(3, 9)],  # 3,4,5 exist
        "one_time_pq_prekeys": [],
    }
    assert a.put("/keys", more).status_code == 204
    assert a.get("/keys/status").json()["one_time_prekeys"] == 8  # existing key ids were ignored
    too_many = {"one_time_prekeys": [{"key_id": i, "public_key": b64(b"k" * 33)} for i in range(100, 301)]}
    assert a.put("/keys", too_many).status_code == 422  # > 200 per request


def test_bundle_fetch_consumes_one_time_keys_then_falls_back(factory):
    a, b = factory.user("alice", keys=False), factory.user("bob", keys=False)
    keys = b.upload_keys(one_time=2)
    seen = []
    for _ in range(3):
        r = a.get(f"/keys/{b.id}")
        assert r.status_code == 200
        (dev,) = r.json()["devices"]
        seen.append(dev)
        assert dev["device_id"] == b.device_id and dev["device_number"] == 1
        assert dev["identity_key"] == keys["identity_key"]
        assert dev["signed_prekey"]["key_id"] == 1
    assert [d["one_time_prekey"]["key_id"] for d in seen[:2]] == [1, 2]
    assert [d["pq_prekey"]["key_id"] for d in seen[:2]] == [1, 2]
    assert seen[2]["one_time_prekey"] is None
    assert seen[2]["pq_prekey"]["public_key"] == keys["pq_last_resort_prekey"]["public_key"]
    assert b.get("/keys/status").json()["one_time_prekeys"] == 0


def test_bundle_fetch_filters(factory):
    a, b = factory.user("alice"), factory.user("bob")
    b2 = factory.login(b, "tablet")
    devs = a.get(f"/keys/{b.id}").json()["devices"]
    assert {d["device_id"] for d in devs} == {b.device_id, b2.device_id}
    one = a.get(f"/keys/{b.id}?device_id={b2.device_id}").json()["devices"]
    assert [d["device_id"] for d in one] == [b2.device_id]
    # own current device is never returned; own other devices are
    mine = b.get(f"/keys/{b.id}").json()["devices"]
    assert [d["device_id"] for d in mine] == [b2.device_id]
    assert a.get(f"/keys/{uuid.uuid4()}").status_code == 404
    assert a.get(f"/keys/{b.id}?device_id=nope").status_code == 422
    # devices without keys are not returned
    factory.login(b, "no keys", keys=False)
    assert len(a.get(f"/keys/{b.id}").json()["devices"]) == 2


def test_identity_key_is_immutable(factory):
    a = factory.user("alice", keys=False)
    keys = a.upload_keys()
    r = a.put("/keys", {"identity_key": b64(b"\x05" + os.urandom(32))})
    assert r.status_code == 409 and r.json()["code"] == "identity_key_mismatch"
    assert a.put("/keys", {"identity_key": keys["identity_key"]}).status_code == 204
    assert a.get("/devices/me").json()["identity_key"] == keys["identity_key"]
    # signed prekey rotation is allowed
    rotated = {"signed_prekey": {"key_id": 2, "public_key": b64(os.urandom(33)), "signature": b64(os.urandom(64))}}
    assert a.put("/keys", rotated).status_code == 204
    assert a.get("/keys/status").json()["signed_prekey_id"] == 2


def test_key_upload_emits_devices_changed_to_conversation_peers(factory):
    a, b = factory.user("alice"), factory.user("bob")
    c = factory.user("carol")  # shares no conversation
    factory.direct(a, b)
    a_second = factory.login(a, "tablet", keys=False)
    cursor_b = b.sync()["next_cursor"]
    cursor_c = c.sync()["next_cursor"]
    a_second.upload_keys()
    evs = b.events("devices_changed", after=cursor_b)
    assert [e["data"] for e in evs] == [{"user_id": a.id}]
    assert c.events("devices_changed", after=cursor_c) == []
    assert a.events("devices_changed")  # the user's other devices learn about it too
    # a pure one-time key top-up does not spam peers
    cursor_b = b.sync()["next_cursor"]
    a_second.put("/keys", {"one_time_prekeys": [{"key_id": 50, "public_key": b64(b"k" * 33)}]})
    assert b.events("devices_changed", after=cursor_b) == []


def test_key_endpoints_require_auth(client):
    assert client.put("/api/v1/keys", json={}).status_code == 401
    assert client.get(f"/api/v1/keys/{uuid.uuid4()}").status_code == 401

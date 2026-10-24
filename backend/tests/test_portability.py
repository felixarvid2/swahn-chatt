"""Things SQLite accepts and PostgreSQL does not (or the other way round); run on both databases."""

import uuid

import pytest

from tests.conftest import PASSWORD

NUL = "bad\x00name"


def test_usernames_are_case_insensitive_everywhere(factory, alice, client):
    r = client.post("/api/v1/auth/login", json={"username": " ALICE ", "password": PASSWORD, "device_name": "d"})
    assert r.status_code == 200 and r.json()["user"]["username"] == "alice"
    taken = client.post(
        "/api/v1/auth/invite/redeem",
        json={
            "code": factory.invite(),
            "username": "AlIcE",
            "password": PASSWORD,
            "display_name": "Imposter",
            "device_name": "d",
        },
    )
    assert taken.status_code == 409 and taken.json()["code"] == "username_taken"
    # the Kelvin sign lower-cases to a plain k, so it cannot be used to shadow a name either
    k = factory.user("kevin")
    clash = client.post(
        "/api/v1/auth/invite/redeem",
        json={
            "code": factory.invite(),
            "username": "Kevin",
            "password": PASSWORD,
            "display_name": "K",
            "device_name": "d",
        },
    )
    assert clash.status_code == 409 and k.username == "kevin"


@pytest.mark.parametrize(
    ("path", "body"),
    [
        ("/auth/login", {"username": "alice", "password": PASSWORD, "device_name": NUL}),
        (
            "/auth/invite/redeem",
            {"code": "X", "username": "bob", "password": PASSWORD, "display_name": NUL, "device_name": "d"},
        ),
    ],
)
def test_nul_characters_are_rejected_not_a_server_error(client, alice, path, body):
    r = client.post("/api/v1" + path, json=body)
    assert r.status_code == 422, r.text


def test_nul_characters_in_profile_group_and_device_names(factory, alice, bob):
    assert alice.patch("/users/me", {"display_name": NUL}).status_code == 422
    assert alice.post("/conversations", {"type": "group", "name": NUL, "member_user_ids": [bob.id]}).status_code == 422
    cid = factory.group(alice, [bob])
    assert alice.patch(f"/conversations/{cid}", {"name": "ok\x00"}).status_code == 422
    assert (
        alice.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": "https://x.example/a\x00b"}).status_code
        == 422
    )
    assert alice.post("/admin/invitations", {"note": NUL}).status_code in (403, 422)


def test_nul_characters_in_path_parameters_are_not_a_server_error(alice):
    for path in (
        "/conversations/a%00b",
        "/users/a%00b/devices",
        "/keys/a%00b",
        "/attachments/a%00b",
        f"/keys/{uuid.uuid4()}?device_id=a%00b",
    ):
        assert alice.get(path).status_code in (404, 422), path
    assert alice.delete("/devices/a%00b").status_code in (404, 422)


def test_database_length_limits_hold_for_long_values(alice, bob, factory):
    """VARCHAR(n) is enforced by PostgreSQL only; every schema limit must be within its column."""
    assert alice.patch("/users/me", {"display_name": "x" * 64}).status_code == 200
    assert alice.patch("/users/me", {"display_name": "x" * 65}).status_code == 422
    big = "https://push.example/" + "a" * 2000
    assert alice.put("/devices/me/push", {"kind": "unifiedpush", "endpoint": big}).status_code in (204, 409, 422)
    cid = factory.direct(alice, bob)
    assert alice.send(cid, "x", id="not-a-uuid").status_code == 422


def test_large_numbers_are_within_bigint(factory, alice, bob):
    cid = factory.direct(alice, bob)
    assert alice.send(cid, "x", client_ts=2**62).status_code == 201
    assert alice.send(cid, "x", client_ts=2**62 + 1).status_code == 422
    assert alice.post(f"/conversations/{cid}/read", {"up_to_seq": 2**62}).status_code == 204
    assert alice.get(f"/sync?after={2**62}").status_code == 200
    assert alice.get(f"/conversations/{cid}/messages?before_seq={2**63}").status_code == 422
    assert alice.get(f"/sync?after={2**63}").status_code == 422

"""Opt-in presence: nothing about a user's online status leaves the server unless they switched it on."""

import time

import pytest
from sqlalchemy import select

from app.models import User

WS = "/api/v1/ws"
SETTLE = 0.4  # comfortably longer than the debounce used in these tests


@pytest.fixture
def pclient(make_app):
    from tests.conftest import Factory

    app, client = make_app(presence_debounce_seconds=0.05)
    return app, client, Factory(app, client)


def connect(client, actor):
    return client.websocket_connect(WS, headers=actor.headers)


def hello(ws):
    assert ws.receive_json()["type"] == "hello"


def share(actor, on=True):
    r = actor.patch("/users/me/settings", {"share_presence": on})
    assert r.status_code == 200 and r.json() == {"share_presence": on}


def assert_quiet(ws):
    """The next frame the socket gets is the pong to our ping: nothing else was queued before it."""
    time.sleep(SETTLE)
    ws.send_json({"type": "ping"})
    assert ws.receive_json() == {"type": "pong"}


def listed(actor, user):
    return next(u for u in actor.get("/users").json() if u["id"] == user.id)


# ---------------------------------------------------------------- the setting


def test_presence_is_off_by_default_and_the_setting_roundtrips(alice, admin):
    assert alice.get("/users/me/settings").json() == {"share_presence": False}
    share(alice, True)
    assert alice.get("/users/me/settings").json() == {"share_presence": True}
    assert alice.patch("/users/me/settings", {}).json() == {"share_presence": True}  # nothing to change
    assert alice.patch("/users/me/settings", {"share_presence": "yes please"}).status_code == 422
    share(alice, False)
    events = [e for e in admin.get("/admin/audit").json() if e["action"] == "presence_setting_changed"]
    assert [e["details"]["share_presence"] for e in events] == [False, True]  # newest first
    assert all(e["actor_user_id"] == alice.id for e in events)


def test_other_response_shapes_are_unchanged(alice):
    assert set(alice.get("/users/me").json()) == {
        "id",
        "username",
        "display_name",
        "avatar_attachment_id",
        "is_admin",
        "created_at",
    }


# ---------------------------------------------------------------- GET /users


def test_users_list_shows_presence_only_for_users_who_opted_in(pclient):
    _app, client, f = pclient
    alice, bob, carol = f.user("alice"), f.user("bob"), f.user("carol")
    share(bob)
    with connect(client, bob) as bws, connect(client, carol) as cws:
        hello(bws)
        hello(cws)
        users = {u["username"]: u for u in alice.get("/users").json()}
        assert users["bob"]["online"] is True and isinstance(users["bob"]["last_seen_at"], int)
        # carol is online as well, but did not opt in: the fields exist and are null
        assert users["carol"]["online"] is None and users["carol"]["last_seen_at"] is None
        assert users["alice"]["online"] is None
    users = {u["username"]: u for u in alice.get("/users").json()}
    assert users["bob"]["online"] is False and isinstance(users["bob"]["last_seen_at"], int)
    assert users["carol"]["online"] is None and users["carol"]["last_seen_at"] is None


def test_opted_in_but_never_seen_is_offline_without_a_time(pclient):
    _app, _client, f = pclient
    alice, bob = f.user("alice"), f.user("bob")
    share(bob)
    assert listed(alice, bob)["online"] is False and listed(alice, bob)["last_seen_at"] is None


# ---------------------------------------------------------------- WebSocket frames


def test_contacts_get_presence_frames_on_connect_and_disconnect(pclient):
    _app, client, f = pclient
    alice, bob = f.user("alice"), f.user("bob")
    f.direct(alice, bob)
    share(bob)
    with connect(client, alice) as aws:
        hello(aws)
        with connect(client, bob) as bws:
            hello(bws)
            frame = aws.receive_json()
            assert frame == {
                "type": "presence",
                "user_id": bob.id,
                "online": True,
                "last_seen_at": frame["last_seen_at"],
            }
            assert isinstance(frame["last_seen_at"], int)
        frame = aws.receive_json()
        assert frame["type"] == "presence" and frame["user_id"] == bob.id and frame["online"] is False
        assert isinstance(frame["last_seen_at"], int)
        assert frame["last_seen_at"] == listed(alice, bob)["last_seen_at"]
        # bob never receives frames about himself
        assert_quiet(aws)


def test_only_users_sharing_a_conversation_are_told(pclient):
    _app, client, f = pclient
    alice, bob, dave = f.user("alice"), f.user("bob"), f.user("dave")
    f.direct(alice, bob)
    share(bob)
    with connect(client, alice) as aws, connect(client, dave) as dws:
        hello(aws)
        hello(dws)
        with connect(client, bob) as bws:
            hello(bws)
            assert aws.receive_json()["online"] is True
            assert_quiet(dws)  # dave shares no conversation with bob
            # (GET /users, which lists everybody, is where dave could still see bob's opted-in status)


def test_not_sharing_means_nothing_leaves_the_server(pclient, app=None):
    app_, client, f = pclient
    alice, bob, carol = f.user("alice"), f.user("bob"), f.user("carol")
    f.direct(alice, bob)
    f.direct(alice, carol)
    share(carol)  # a sharing contact as the positive control: the observer does receive frames, just not bob's
    with connect(client, alice) as aws:
        hello(aws)
        with connect(client, bob) as bws:  # bob has not opted in
            hello(bws)
            with connect(client, carol) as cws:
                hello(cws)
                frame = aws.receive_json()
                assert frame["user_id"] == carol.id and frame["online"] is True
        frame = aws.receive_json()
        assert frame["user_id"] == carol.id and frame["online"] is False
        assert_quiet(aws)  # nothing about bob, neither on connect nor on disconnect
    # no frame, no list entry, no stored timestamp, no device activity time
    assert listed(alice, bob)["online"] is None and listed(alice, bob)["last_seen_at"] is None
    with app_.state.session_factory() as db:
        assert db.scalar(select(User.presence_last_seen_at).where(User.id == bob.id)) is None
    (dev,) = alice.get(f"/users/{bob.id}/devices").json()
    assert dev["last_seen_at"] == dev["created_at"]
    # while the owner (and nobody else) still sees the real activity time
    (own,) = bob.get(f"/users/{bob.id}/devices").json()
    assert own["last_seen_at"] >= own["created_at"]


def test_device_activity_time_is_visible_for_users_who_share(pclient):
    _app, _client, f = pclient
    alice, bob = f.user("alice"), f.user("bob")
    share(bob)
    from app.models import Device

    with _app.state.session_factory() as db:
        dev = db.scalar(select(Device).where(Device.user_id == bob.id))
        dev.last_seen_at = dev.created_at + 12_345
        db.commit()
    assert alice.get(f"/users/{bob.id}/devices").json()[0]["last_seen_at"] == dev.created_at + 12_345


def test_second_device_keeps_the_user_online(pclient):
    _app, client, f = pclient
    alice, bob = f.user("alice"), f.user("bob")
    bob2 = f.login(bob, "tablet")
    f.direct(alice, bob)
    share(bob)
    with connect(client, alice) as aws:
        hello(aws)
        with connect(client, bob) as b1, connect(client, bob2) as b2:
            hello(b1)
            hello(b2)
            assert aws.receive_json()["online"] is True
            b2.close()
            assert_quiet(aws)  # still online through the first device
            assert listed(alice, bob)["online"] is True
        assert aws.receive_json()["online"] is False


def test_switching_it_on_while_connected_announces_and_switching_off_retracts(pclient):
    app_, client, f = pclient
    alice, bob = f.user("alice"), f.user("bob")
    f.direct(alice, bob)
    with connect(client, alice) as aws, connect(client, bob) as bws:
        hello(aws)
        hello(bws)
        assert_quiet(aws)  # bob does not share yet
        share(bob, True)
        frame = aws.receive_json()
        assert frame["user_id"] == bob.id and frame["online"] is True
        assert listed(alice, bob)["online"] is True
        share(bob, False)
        # one retraction: "unknown" again, exactly what any non-sharing user looks like
        assert aws.receive_json() == {"type": "presence", "user_id": bob.id, "online": False, "last_seen_at": None}
        assert listed(alice, bob)["online"] is None
        with app_.state.session_factory() as db:
            assert db.scalar(select(User.presence_last_seen_at).where(User.id == bob.id)) is None
        # and from here on nothing, whatever bob does
        with connect(client, bob) as again:
            hello(again)
        assert_quiet(aws)


def test_switching_off_when_nothing_was_announced_sends_nothing(pclient):
    _app, client, f = pclient
    alice, bob = f.user("alice"), f.user("bob")
    f.direct(alice, bob)
    with connect(client, alice) as aws:
        hello(aws)
        share(bob, True)  # bob is offline: nothing to announce
        share(bob, False)
        assert_quiet(aws)


def test_flapping_connections_are_debounced(make_app):
    from tests.conftest import Factory

    app_, client = make_app(presence_debounce_seconds=0.6)
    f = Factory(app_, client)
    alice, bob = f.user("alice"), f.user("bob")
    f.direct(alice, bob)
    share(bob)
    with connect(client, alice) as aws:
        hello(aws)
        with connect(client, bob) as b:
            hello(b)
        assert aws.receive_json()["online"] is True  # the first change goes out at once
        with connect(client, bob) as b:  # back within the debounce window: no offline/online noise
            hello(b)
            time.sleep(0.9)
            assert_quiet(aws)
        # a real disconnect is announced once the window has passed
        assert aws.receive_json()["online"] is False

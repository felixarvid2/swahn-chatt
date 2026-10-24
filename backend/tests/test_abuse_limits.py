"""Limits against abuse: one-time prekey draining, device spam, password guessing, IPv6 address rotation."""

from fastapi.testclient import TestClient

from app.ratelimit import RateLimiter, rate_key
from tests.conftest import PASSWORD, Factory, b64

LOGIN = "/api/v1/auth/login"


def bad_login(client, username="alice", **kw):
    return client.post(LOGIN, json={"username": username, "password": "wrong-password-xyz", "device_name": "x"}, **kw)


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def with_clock(app) -> Clock:
    clock = Clock()
    app.state.limiter._clock = clock
    return clock


# ---------------------------------------------------------------- prekey draining


def test_one_time_prekeys_cannot_be_drained_by_logging_in_again(make_app):
    app, client = make_app(rate_limit_enabled=True, max_devices_per_user=50)
    f = Factory(app, client)
    victim, attacker = f.user("victim"), f.user("attacker")
    victim.put("/keys", {"one_time_prekeys": [{"key_id": 100 + i, "public_key": b64(b"k" * 33)} for i in range(195)]})
    before = victim.get("/keys/status").json()["one_time_prekeys"]
    assert before == 200
    devices = [attacker] + [f.login(attacker, keys=False) for _ in range(3)]  # every login is a new device
    fetched = []
    for n in range(40):
        r = devices[n % len(devices)].get(f"/keys/{victim.id}")
        assert r.status_code == 200
        (bundle,) = r.json()["devices"]
        fetched.append(bundle)
    with_otk = [b for b in fetched if b["one_time_prekey"] is not None]
    assert len(with_otk) == 10  # KEY_BUNDLES_PER_TARGET_HOUR, however many devices the caller owns
    assert fetched[:10] == with_otk
    assert victim.get("/keys/status").json()["one_time_prekeys"] == before - 10
    # beyond the share the bundle is still usable: signed prekey + last-resort PQ key, no one-time keys
    last = fetched[-1]
    assert last["one_time_prekey"] is None
    assert (
        last["signed_prekey"]["public_key"]
        and last["pq_prekey"]["key_id"] == victim.extra["keys"]["pq_last_resort_prekey"]["key_id"]
    )
    # a different caller has its own share
    other = f.user("other")
    assert other.get(f"/keys/{victim.id}").json()["devices"][0]["one_time_prekey"] is not None


def test_key_share_is_per_target_device_and_decays(make_app):
    app, client = make_app(rate_limit_enabled=True)
    clock = with_clock(app)
    f = Factory(app, client)
    a, b = f.user("alice"), f.user("bob")
    b2 = f.login(b, "tablet")
    for actor in (b, b2):
        actor.put("/keys", {"one_time_prekeys": [{"key_id": 100 + i, "public_key": b64(b"k" * 33)} for i in range(40)]})
    for _ in range(10):
        devs = a.get(f"/keys/{b.id}").json()["devices"]
        assert all(d["one_time_prekey"] for d in devs)
    devs = a.get(f"/keys/{b.id}").json()["devices"]
    assert len(devs) == 2 and all(d["one_time_prekey"] is None for d in devs)
    # the share is counted per target device; after the window it is back
    clock.t += 3601
    devs = a.get(f"/keys/{b.id}").json()["devices"]
    assert all(d["one_time_prekey"] for d in devs)
    assert b2.device_id in {d["device_id"] for d in devs}


def test_key_fetch_requests_are_limited_per_user_not_per_device(make_app):
    app, client = make_app(rate_limit_enabled=True)
    f = Factory(app, client)
    a, b = f.user("alice"), f.user("bob")
    a2 = f.login(a, keys=False)
    codes = [(a if i % 2 else a2).get(f"/keys/{b.id}").status_code for i in range(121)]
    assert codes[119] == 200 and codes[120] == 429


# ---------------------------------------------------------------- device limit


def test_device_limit_blocks_login_with_a_clear_error(make_app):
    app, client = make_app(max_devices_per_user=3)
    f = Factory(app, client)
    a = f.user("alice")
    second, third = f.login(a, "two", keys=False), f.login(a, "three", keys=False)
    body = {"username": "alice", "password": PASSWORD, "device_name": "four"}
    r = client.post(LOGIN, json=body)
    assert r.status_code == 409 and r.json()["code"] == "device_limit"
    assert "3" in r.json()["message"] and "remove" in r.json()["message"]
    # wrong passwords still look like wrong passwords: the limit is not revealed to a guesser
    assert bad_login(client).json()["code"] == "invalid_credentials"
    # removing a device frees a slot; revoked devices do not count
    assert third.delete(f"/devices/{second.device_id}").status_code == 204
    assert client.post(LOGIN, json=body).status_code == 200
    assert client.post(LOGIN, json=body).status_code == 409


def test_default_device_limit_is_ten(make_app):
    app, client = make_app()
    assert app.state.settings.max_devices_per_user == 10
    f = Factory(app, client)
    a = f.user("alice")
    for i in range(9):
        f.login(a, f"d{i}", keys=False)
    r = client.post(LOGIN, json={"username": "alice", "password": PASSWORD, "device_name": "eleventh"})
    assert r.status_code == 409 and r.json()["code"] == "device_limit"


def test_device_limit_is_per_user_and_configurable_by_env(monkeypatch, tmp_path):
    from app.config import Settings

    monkeypatch.setenv("FAMILYCHAT_MAX_DEVICES_PER_USER", "4")
    assert Settings(env="development", data_dir=tmp_path).max_devices_per_user == 4


# ---------------------------------------------------------------- per-username lockout


def test_username_is_locked_after_ten_failures_whatever_the_ip(make_app):
    app, client = make_app(rate_limit_enabled=True, trusted_proxies="172.16.0.0/12")
    proxy = TestClient(app, client=("172.16.0.9", 4000))  # peer is the trusted proxy, X-Forwarded-For names the client
    f = Factory(app, client)
    f.user("alice")
    for i in range(10):
        r = bad_login(proxy, headers={"X-Forwarded-For": f"203.0.113.{i + 1}"})
        assert r.status_code == 401, i
    locked = bad_login(proxy, headers={"X-Forwarded-For": "198.51.100.77"})
    assert locked.status_code == 429 and locked.json()["code"] == "rate_limited"
    assert int(locked.headers["Retry-After"]) >= 1
    # even the right password from a brand new address is refused while the lock lasts
    ok = proxy.post(
        LOGIN,
        json={"username": "alice", "password": PASSWORD, "device_name": "x"},
        headers={"X-Forwarded-For": "198.51.100.78"},
    )
    assert ok.status_code == 429
    # other accounts are unaffected
    assert bad_login(proxy, "bob", headers={"X-Forwarded-For": "198.51.100.79"}).status_code == 401


def test_lockout_is_identical_for_unknown_usernames(make_app):
    app, client = make_app(rate_limit_enabled=True)
    clock = with_clock(app)
    f = Factory(app, client)
    f.user("alice")
    seen = {}
    for name in ("alice", "ghost"):
        for i in range(10):
            clock.t += 61  # stay under the per-IP limits; only the per-username counter accumulates
            assert bad_login(client, name).status_code == 401, (name, i)
        clock.t += 61
        r = bad_login(client, name)
        body = {k: v for k, v in r.json().items() if k != "request_id"}  # differs per request by design
        seen[name] = (r.status_code, body, set(r.headers) & {"retry-after"})
    assert seen["alice"][0] == seen["ghost"][0] == 429
    assert seen["alice"][1] == seen["ghost"][1]
    assert seen["alice"][2] == seen["ghost"][2] == {"retry-after"}


def test_lockout_decays_and_a_longer_one_follows_repeated_abuse(make_app):
    app, client = make_app(rate_limit_enabled=True)
    clock = with_clock(app)
    f = Factory(app, client)
    f.user("alice")
    good = {"username": "alice", "password": PASSWORD, "device_name": "x"}

    def burst(n):
        for _ in range(n):
            clock.t += 61
            assert bad_login(client).status_code == 401

    burst(10)
    clock.t += 61
    assert bad_login(client).status_code == 429
    clock.t += 3600  # an hour after the failures: the hourly counter has emptied
    assert client.post(LOGIN, json=good).status_code == 200
    # but a day's worth of failures (30) keeps the account locked for longer
    burst(10)
    clock.t += 3601
    burst(10)  # 30 failures within the day in total
    clock.t += 3601
    assert client.post(LOGIN, json=good).status_code == 429
    clock.t += 86400
    assert client.post(LOGIN, json=good).status_code == 200


def test_successful_logins_do_not_count_as_failures(make_app):
    app, client = make_app(rate_limit_enabled=True)
    clock = with_clock(app)
    f = Factory(app, client)
    a = f.user("alice")
    for _ in range(14):
        clock.t += 61
        r = client.post(LOGIN, json={"username": "alice", "password": PASSWORD, "device_name": "x"})
        assert r.status_code in (200, 409)  # 409: device limit, still not a failure
    clock.t += 61
    assert bad_login(client).status_code == 401
    assert a.get("/users/me").status_code == 200  # existing sessions are never locked out


# ---------------------------------------------------------------- IPv6 grouping


def test_rate_key_groups_ipv6_by_slash_64():
    assert rate_key("2001:db8:1:2::1") == rate_key("2001:db8:1:2:ffff:ffff:ffff:ffff") == "2001:db8:1:2::/64"
    assert rate_key("2001:db8:1:3::1") != rate_key("2001:db8:1:2::1")
    assert rate_key("::ffff:192.0.2.7") == "192.0.2.7" == rate_key("192.0.2.7")
    assert rate_key("fe80::1%eth0") == rate_key("fe80::2")
    assert rate_key("unknown") == "unknown" and rate_key("testclient") == "testclient"


def test_rotating_ipv6_addresses_in_one_prefix_share_one_limit(make_app):
    app, client = make_app(rate_limit_enabled=True, trusted_proxies="172.16.0.0/12")
    proxy = TestClient(app, client=("172.16.0.9", 4000))
    codes = [
        bad_login(proxy, f"user{i}", headers={"X-Forwarded-For": f"2001:db8:aa:bb::{i + 1:x}"}).status_code
        for i in range(12)
    ]
    assert codes[:10] == [401] * 10 and codes[10:] == [429, 429]
    # another /64 is a different client
    assert bad_login(proxy, "x", headers={"X-Forwarded-For": "2001:db8:aa:cc::1"}).status_code == 401
    # while the access log keeps the exact address
    from starlette.requests import Request

    from app.ratelimit import client_ip

    req = Request(
        {"type": "http", "client": ("172.16.0.9", 1), "headers": [(b"x-forwarded-for", b"2001:db8:aa:bb::7")]}
    )
    assert client_ip(req, app.state.settings.trusted_networks) == "2001:db8:aa:bb::7"


# ---------------------------------------------------------------- limiter internals


def test_limiter_cleanup_respects_each_keys_window():
    t = [0.0]
    lim = RateLimiter(clock=lambda: t[0])
    lim.record("daily", 86400.0)
    lim.record("hourly", 3600.0)
    t[0] += 7200
    lim.cleanup()
    assert "hourly" not in lim._hits and "daily" in lim._hits
    t[0] += 86400
    lim.cleanup()
    assert not lim._hits


def test_allow_does_not_record_denied_attempts():
    t = [0.0]
    lim = RateLimiter(clock=lambda: t[0])
    assert [lim.allow("k", 2, 10) for _ in range(4)] == [True, True, False, False]
    t[0] += 10.5
    assert lim.allow("k", 2, 10)

"""Multi-use invitations: atomic use counting, expiry and revocation still apply, every use is audited."""

import threading

from sqlalchemy import update

from app import clock
from app.models import Invitation
from tests.conftest import PASSWORD

REDEEM = "/api/v1/auth/invite/redeem"


def redeem(client, code, name):
    return client.post(
        REDEEM,
        json={"code": code, "username": name, "password": PASSWORD, "display_name": name, "device_name": "phone"},
    )


def invite(admin, **body):
    r = admin.post("/admin/invitations", body)
    assert r.status_code == 201, r.text
    return r.json()


def listed(admin, inv_id):
    return next(i for i in admin.get("/admin/invitations").json() if i["id"] == inv_id)


def test_default_is_still_a_single_use_invitation(client, admin):
    inv = invite(admin, note="old style")
    assert inv["max_uses"] == 1
    assert redeem(client, inv["code"], "first").status_code == 201
    r = redeem(client, inv["code"], "second")
    assert r.status_code == 404 and r.json()["code"] == "invalid_invite"
    row = listed(admin, inv["id"])
    assert (row["max_uses"], row["uses"], row["remaining"]) == (1, 1, 0)
    assert row["used_at"] is not None and row["used_by"] is not None


def test_a_code_works_max_uses_times(client, admin):
    inv = invite(admin, max_uses=3)
    assert inv["max_uses"] == 3
    users = []
    for i, name in enumerate(["user1", "user2", "user3"], start=1):
        r = redeem(client, inv["code"], name)
        assert r.status_code == 201, r.text
        users.append(r.json()["user"]["id"])
        row = listed(admin, inv["id"])
        assert (row["uses"], row["remaining"]) == (i, 3 - i)
        assert (row["used_at"] is None) == (i < 3)  # used_at / used_by: "used up" and the latest redeemer
    assert listed(admin, inv["id"])["used_by"] == users[-1]
    assert redeem(client, inv["code"], "user4").status_code == 404
    # every use is audited with the new user's id
    events = [e for e in admin.get("/admin/audit?limit=100").json() if e["action"] == "invitation_redeemed"]
    assert sorted(e["target_user_id"] for e in events if e["target_id"] == inv["id"]) == sorted(users)
    assert sorted(e["details"]["use"] for e in events if e["target_id"] == inv["id"]) == [1, 2, 3]
    assert all(e["details"]["max_uses"] == 3 for e in events if e["target_id"] == inv["id"])


def test_a_taken_username_does_not_burn_a_use(client, admin):
    inv = invite(admin, max_uses=2)
    assert redeem(client, inv["code"], "same").status_code == 201
    assert redeem(client, inv["code"], "same").status_code == 409
    assert listed(admin, inv["id"])["uses"] == 1
    assert redeem(client, inv["code"], "other").status_code == 201


def test_revoking_stops_the_remaining_uses(client, admin):
    inv = invite(admin, max_uses=5)
    assert redeem(client, inv["code"], "user1").status_code == 201
    assert admin.delete(f"/admin/invitations/{inv['id']}").status_code == 204
    assert redeem(client, inv["code"], "user2").status_code == 404
    row = listed(admin, inv["id"])
    assert row["uses"] == 1 and row["remaining"] == 0 and row["revoked_at"] is not None


def test_expiry_still_applies_to_unused_uses(app, client, admin):
    inv = invite(admin, max_uses=5)
    assert redeem(client, inv["code"], "user1").status_code == 201
    with app.state.session_factory() as db:
        db.execute(update(Invitation).where(Invitation.id == inv["id"]).values(expires_at=clock.now_ms() - 1))
        db.commit()
    assert redeem(client, inv["code"], "user2").status_code == 404
    assert listed(admin, inv["id"])["remaining"] == 0


def test_validation(admin):
    for bad in (0, 21, -1, "many"):
        assert admin.post("/admin/invitations", {"max_uses": bad}).status_code == 422, bad
    assert admin.post("/admin/invitations", {"max_uses": 20}).status_code == 201
    assert admin.post("/admin/invitations", {"max_uses": 1, "is_admin": True}).status_code == 201
    r = admin.post("/admin/invitations", {"max_uses": 2, "is_admin": True})
    assert r.status_code == 422  # administrator invitations stay single use


def test_creation_is_audited_with_max_uses(admin):
    invite(admin, max_uses=4)
    created = [e for e in admin.get("/admin/audit").json() if e["action"] == "invitation_created"]
    assert created[0]["details"]["max_uses"] == 4


def test_concurrent_redemptions_never_exceed_max_uses(client, admin):
    inv = invite(admin, max_uses=3)
    n = 9
    start, codes = threading.Barrier(n), []

    def go(i):
        start.wait()
        codes.append(redeem(client, inv["code"], f"racer{i}").status_code)

    threads = [threading.Thread(target=go, args=(i,)) for i in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(60)
    assert sorted(codes) == [201] * 3 + [404] * (n - 3), codes
    row = listed(admin, inv["id"])
    assert (row["uses"], row["remaining"]) == (3, 0) and row["used_at"] is not None
    users = [u for u in admin.get("/admin/users").json() if u["username"].startswith("racer")]
    assert len(users) == 3

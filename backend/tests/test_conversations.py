import uuid

from tests.conftest import b64

NOPE = "00000000-0000-4000-8000-000000000000"


def test_direct_conversation_is_idempotent_and_symmetric(factory, alice, bob):
    r1 = alice.post("/conversations", {"type": "direct", "member_user_ids": [bob.id]})
    assert r1.status_code == 201
    conv = r1.json()
    assert conv["type"] == "direct" and conv["name"] is None
    assert {m["user_id"] for m in conv["members"]} == {alice.id, bob.id}
    assert conv["unread_count"] == 0 and conv["my_read_seq"] == 0 and conv["last_message_seq"] is None
    r2 = bob.post("/conversations", {"type": "direct", "member_user_ids": [alice.id]})
    assert r2.status_code == 200 and r2.json()["id"] == conv["id"]
    assert [c["id"] for c in alice.get("/conversations").json()] == [conv["id"]]
    # validation
    assert alice.post("/conversations", {"type": "direct", "member_user_ids": [alice.id]}).status_code == 422
    assert alice.post("/conversations", {"type": "direct", "member_user_ids": []}).status_code == 422
    assert alice.post("/conversations", {"type": "direct", "member_user_ids": [NOPE]}).status_code == 404


def test_group_creation_and_admin_rules(factory, alice, bob, carol):
    conv = alice.post("/conversations", {"type": "group", "name": "Familjen", "member_user_ids": [bob.id, carol.id]})
    assert conv.status_code == 201
    body = conv.json()
    roles = {m["user_id"]: m["role"] for m in body["members"]}
    assert roles == {alice.id: "admin", bob.id: "member", carol.id: "member"}
    assert alice.post("/conversations", {"type": "group", "member_user_ids": [bob.id]}).status_code == 422
    assert alice.post("/conversations", {"type": "group", "name": "x" * 65, "member_user_ids": []}).status_code == 422
    cid = body["id"]
    # only admins rename / add / change roles
    assert bob.patch(f"/conversations/{cid}", {"name": "Hijack"}).status_code == 403
    assert bob.post(f"/conversations/{cid}/members", {"user_ids": [bob.id]}).status_code == 403
    assert bob.patch(f"/conversations/{cid}/members/{carol.id}", {"role": "admin"}).status_code == 403
    r = alice.patch(f"/conversations/{cid}", {"name": "Släkten"})
    assert r.status_code == 200 and r.json()["name"] == "Släkten"
    assert alice.patch(f"/conversations/{cid}", {"name": None}).status_code == 422
    # every member sees the rename as an event
    assert any(e["type"] == "conversation_updated" and e["data"]["name"] == "Släkten" for e in carol.events())


def test_non_member_gets_404_on_every_route(factory, alice, bob, carol):
    direct = factory.direct(alice, bob)
    group = factory.group(alice, [bob])
    for cid in (direct, group, NOPE):  # existing and non-existing conversations look the same
        calls = [
            ("GET", f"/conversations/{cid}", None),
            ("PATCH", f"/conversations/{cid}", {"name": "x"}),
            ("POST", f"/conversations/{cid}/members", {"user_ids": [carol.id]}),
            ("PATCH", f"/conversations/{cid}/members/{alice.id}", {"role": "member"}),
            ("DELETE", f"/conversations/{cid}/members/{alice.id}", None),
            ("DELETE", f"/conversations/{cid}/members/{carol.id}", None),
            ("GET", f"/conversations/{cid}/messages", None),
            ("POST", f"/conversations/{cid}/read", {"up_to_seq": 1}),
        ]
        for method, path, body in calls:
            r = carol.req(method, path, json=body)
            assert r.status_code == 404, (method, path, r.status_code)
            assert r.json()["code"] == "not_found"
        body = {
            "id": str(uuid.uuid4()),
            "conversation_id": cid,
            "kind": "message",
            "client_ts": 1,
            "envelopes": [],
        }
        assert carol.post("/messages", body).status_code == 404
    assert carol.get("/conversations").json() == []


def test_add_member_requires_new_devices_in_envelopes(factory, alice, bob, carol):
    cid = factory.group(alice, [bob])
    first = alice.send(cid, "before carol")
    assert first.status_code == 201
    r = alice.post(f"/conversations/{cid}/members", {"user_ids": [carol.id, carol.id, bob.id]})
    assert r.status_code == 200
    assert {m["user_id"] for m in r.json()["members"]} == {alice.id, bob.id, carol.id}
    # the old envelope set is now incomplete: carol's device is missing
    stale = [{"device_id": bob.device_id, "type": "whisper", "body": b64(b"x")}]
    resp = alice.post(
        "/messages",
        {"id": str(uuid.uuid4()), "conversation_id": cid, "kind": "message", "client_ts": 1, "envelopes": stale},
    )
    assert resp.status_code == 409
    j = resp.json()
    assert j["code"] == "mismatched_devices"
    assert j["missing_devices"] == [{"user_id": carol.id, "device_id": carol.device_id}]
    assert j["extra_devices"] == []
    assert alice.send(cid, "with carol").status_code == 201
    # carol never sees what was sent before she joined
    assert first.json()["message"]["id"] not in [e["data"]["id"] for e in carol.events("message")]
    msgs = carol.get(f"/conversations/{cid}/messages").json()
    assert len(msgs) == 1
    assert carol.get(f"/conversations/{cid}").json()["unread_count"] == 1
    assert alice.post(f"/conversations/{cid}/members", {"user_ids": [NOPE]}).status_code == 404


def test_removed_member_gets_conversation_removed_and_is_excluded(factory, alice, bob, carol):
    cid = factory.group(alice, [bob, carol])
    alice.send(cid, "one")
    assert bob.events("message")  # bob has an undelivered message queued
    r = alice.delete(f"/conversations/{cid}/members/{bob.id}")
    assert r.status_code == 204
    evs = [e for e in bob.sync()["events"] if e["conversation_id"] == cid]
    assert [e["type"] for e in evs] == ["conversation_removed"]  # queued conversation data was purged
    assert evs[0]["data"] == {"conversation_id": cid}
    assert bob.get(f"/conversations/{cid}").status_code == 404
    # envelope set no longer includes bob; sending to him is an "extra" device
    m = alice.send(cid, "two")
    assert m.status_code == 201
    stale = alice.post(
        "/messages",
        {
            "id": str(uuid.uuid4()),
            "conversation_id": cid,
            "kind": "message",
            "client_ts": 1,
            "envelopes": [
                {"device_id": bob.device_id, "type": "whisper", "body": b64(b"x")},
                {"device_id": carol.device_id, "type": "whisper", "body": b64(b"x")},
            ],
        },
    )
    assert stale.status_code == 409
    assert stale.json()["extra_devices"] == [{"user_id": bob.id, "device_id": bob.device_id}]
    assert not [e for e in bob.events() if e["type"] == "message"]
    assert len(carol.get(f"/conversations/{cid}").json()["members"]) == 2
    # a non-admin cannot remove others, but can leave
    assert carol.delete(f"/conversations/{cid}/members/{alice.id}").status_code == 403
    assert alice.delete(f"/conversations/{cid}/members/{bob.id}").status_code == 404
    assert carol.delete(f"/conversations/{cid}/members/{carol.id}").status_code == 204
    assert any(e["type"] == "conversation_removed" for e in carol.events())


def test_last_admin_leaving_promotes_longest_standing_member(factory, alice, bob, carol):
    cid = factory.group(alice, [bob])
    assert alice.post(f"/conversations/{cid}/members", {"user_ids": [carol.id]}).status_code == 200
    assert alice.delete(f"/conversations/{cid}/members/{alice.id}").status_code == 204
    roles = {m["user_id"]: m["role"] for m in bob.get(f"/conversations/{cid}").json()["members"]}
    assert roles == {bob.id: "admin", carol.id: "member"}
    assert alice.get(f"/conversations/{cid}").status_code == 404
    # last member leaving deletes the conversation
    assert carol.delete(f"/conversations/{cid}/members/{carol.id}").status_code == 204
    assert bob.delete(f"/conversations/{cid}/members/{bob.id}").status_code == 204
    assert bob.get(f"/conversations/{cid}").status_code == 404


def test_role_changes_and_last_admin_protection(factory, alice, bob):
    cid = factory.group(alice, [bob])
    assert alice.patch(f"/conversations/{cid}/members/{alice.id}", {"role": "member"}).status_code == 409
    assert alice.patch(f"/conversations/{cid}/members/{bob.id}", {"role": "admin"}).status_code == 200
    assert bob.patch(f"/conversations/{cid}/members/{alice.id}", {"role": "member"}).status_code == 200
    assert alice.patch(f"/conversations/{cid}", {"name": "x"}).status_code == 403
    assert bob.patch(f"/conversations/{cid}/members/{NOPE}", {"role": "admin"}).status_code == 404


def test_direct_conversation_membership_is_fixed(factory, alice, bob, carol):
    cid = factory.direct(alice, bob)
    assert alice.post(f"/conversations/{cid}/members", {"user_ids": [carol.id]}).status_code == 409
    assert alice.delete(f"/conversations/{cid}/members/{alice.id}").status_code == 409
    assert alice.patch(f"/conversations/{cid}", {"name": "x"}).status_code == 409


def test_conversation_list_orders_by_activity(factory, alice, bob, carol):
    c1 = factory.direct(alice, bob)
    c2 = factory.direct(alice, carol)
    assert [c["id"] for c in alice.get("/conversations").json()] == [c2, c1]
    alice.send(c1, "bump")
    assert [c["id"] for c in alice.get("/conversations").json()] == [c1, c2]

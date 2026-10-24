"""A device that polls with its cursor while others write concurrently must end up with every event, on SQLite and
PostgreSQL (see docs/ARCHITECTURE.md, "Sync cursor and the database")."""

import threading
import time

SENDERS = 4
PER_SENDER = 12


def test_polling_with_a_cursor_never_misses_events_under_concurrent_writers(factory, alice, bob, carol):
    gid = factory.group(alice, [bob, carol])
    extra = [factory.user(f"sender{i}") for i in range(SENDERS)]
    for e in extra:
        assert alice.post(f"/conversations/{gid}/members", {"user_ids": [e.id]}).status_code == 200
    sent: set[str] = set()
    lock = threading.Lock()
    errors: list[BaseException] = []
    done = threading.Event()

    def sender(actor):
        try:
            for i in range(PER_SENDER):
                r = actor.send(gid, f"{actor.username}-{i}")
                assert r.status_code == 201, r.text
                with lock:
                    sent.add(r.json()["message"]["id"])
        except BaseException as exc:
            errors.append(exc)

    received: list[str] = []
    cursor = 0

    def poll_once():
        nonlocal cursor
        page = bob.sync(cursor, limit=7)
        received.extend(e["data"]["id"] for e in page["events"] if e["type"] == "message")
        cursor = page["next_cursor"]
        return page["has_more"]

    def poller():
        try:
            while not done.is_set():
                while poll_once():
                    pass
                time.sleep(0.005)
        except BaseException as exc:
            errors.append(exc)

    p = threading.Thread(target=poller)
    p.start()
    threads = [threading.Thread(target=sender, args=(a,)) for a in extra]
    [t.start() for t in threads]
    [t.join(120) for t in threads]
    done.set()
    p.join(30)
    assert not errors, errors
    while poll_once():
        pass
    poll_once()
    assert len(sent) == SENDERS * PER_SENDER
    assert sorted(received) == sorted(sent), "some event was skipped or delivered twice"

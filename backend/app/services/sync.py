"""Per-device event queue: read (GET /sync) and acknowledge (POST /sync/ack)."""

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app import clock
from app.deps import Principal
from app.models import ConversationMember, DeliveryReceipt, Device, Event, Message, MessageEnvelope, User
from app.services.events import active_device_ids, emit_receipt
from app.services.views import conversations_out, messages_out, user_out


def latest_cursor(db: Session, device_id: str) -> int:
    top = db.scalar(select(func.max(Event.seq)).where(Event.device_id == device_id))
    acked = db.scalar(select(Device.acked_cursor).where(Device.id == device_id)) or 0
    return max(top or 0, acked)


def read_events(db: Session, p: Principal, after: int, limit: int) -> dict:
    rows = db.scalars(
        select(Event).where(Event.device_id == p.device_id, Event.seq > after).order_by(Event.seq).limit(limit + 1)
    ).all()
    has_more = len(rows) > limit
    rows = rows[:limit]

    member_of = set(
        db.scalars(select(ConversationMember.conversation_id).where(ConversationMember.user_id == p.user_id))
    )
    msg_ids = [r.payload["message_id"] for r in rows if r.type == "message"]
    messages = {m.id: m for m in db.scalars(select(Message).where(Message.id.in_(msg_ids)))} if msg_ids else {}
    rendered = {m["id"]: m for m in messages_out(db, list(messages.values()), p.device_id)}
    conv_ids = {r.payload["conversation_id"] for r in rows if r.type == "conversation_updated"}
    convs = {c["id"]: c for c in conversations_out(db, conv_ids, p.user_id)}
    user_ids = {r.payload["user_id"] for r in rows if r.type == "user_updated"}
    users = (
        {u.id: u for u in db.scalars(select(User).where(User.id.in_(user_ids), User.disabled_at.is_(None)))}
        if user_ids
        else {}
    )

    events = []
    for r in rows:
        data: dict | None
        if r.type == "message":
            data = rendered.get(r.payload["message_id"])
            if data is None or data["conversation_id"] not in member_of:
                data = None
            elif data["kind"] in ("edit", "reaction") and data["envelope"] is None:
                data = None  # its target was deleted before delivery
        elif r.type == "conversation_updated":
            data = convs.get(r.payload["conversation_id"])
        elif r.type == "user_updated":
            u = users.get(r.payload["user_id"])
            data = user_out(u) if u else None
        elif r.type == "receipt" and r.conversation_id not in member_of:
            data = None
        else:
            data = r.payload
        if data is None:
            continue  # stale (the thing it described is gone); the cursor still advances
        events.append(
            {
                "seq": r.seq,
                "type": r.type,
                "created_at": r.created_at,
                "conversation_id": r.conversation_id,
                "data": data,
            }
        )
    next_cursor = rows[-1].seq if rows else after
    return {"events": events, "next_cursor": next_cursor, "has_more": has_more}


def ack(db: Session, p: Principal, cursor: int) -> None:
    """Mark envelopes in events up to `cursor` delivered, emit delivered receipts, drop the acked events."""
    device = db.get(Device, p.device_id)
    cursor = min(cursor, latest_cursor(db, p.device_id))
    if cursor <= 0:
        return
    now = clock.now_ms()
    msg_ids = [
        e.payload["message_id"]
        for e in db.scalars(
            select(Event).where(Event.device_id == p.device_id, Event.seq <= cursor, Event.type == "message")
        )
    ]
    if msg_ids:
        envelopes = db.scalars(
            select(MessageEnvelope).where(
                MessageEnvelope.device_id == p.device_id,
                MessageEnvelope.message_id.in_(msg_ids),
                MessageEnvelope.delivered_at.is_(None),
            )
        ).all()
        newly = {e.message_id for e in envelopes}
        for e in envelopes:
            e.delivered_at = now
        db.flush()
        _delivery_receipts(db, p, newly, now)
    db.execute(delete(Event).where(Event.device_id == p.device_id, Event.seq <= cursor))
    device.acked_cursor = max(device.acked_cursor, cursor)
    db.flush()


def _delivery_receipts(db: Session, p: Principal, message_ids: set[str], now: int) -> None:
    if not message_ids:
        return
    msgs = db.scalars(
        select(Message).where(
            Message.id.in_(message_ids), Message.kind == "message", Message.sender_user_id != p.user_id
        )
    ).all()
    if not msgs:
        return
    done = set(
        db.scalars(
            select(DeliveryReceipt.message_id).where(
                DeliveryReceipt.message_id.in_([m.id for m in msgs]), DeliveryReceipt.user_id == p.user_id
            )
        )
    )
    grouped: dict[tuple[str, str], list[str]] = {}
    for m in msgs:
        if m.id in done:
            continue
        db.add(DeliveryReceipt(message_id=m.id, user_id=p.user_id, delivered_at=now))
        grouped.setdefault((m.sender_user_id, m.conversation_id), []).append(m.id)
    db.flush()
    for (sender, conv_id), ids in grouped.items():
        emit_receipt(
            db,
            active_device_ids(db, [sender]),
            {"conversation_id": conv_id, "user_id": p.user_id, "type": "delivered", "message_ids": ids},
            conv_id,
        )

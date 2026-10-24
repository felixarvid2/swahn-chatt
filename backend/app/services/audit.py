"""Audit log: who did what to whom, and from where. Rows are written in the caller's transaction (so they commit
or roll back with the action) unless the caller commits them on its own, as for failed logins.

`details` must never hold secrets: no passwords, invitation codes, tokens or user supplied usernames of unknown
accounts. Keep it to ids, flags and short fixed words."""

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import clock
from app.models import AuditEvent

UNKNOWN_USER = "unknown user"


def record(
    db: Session,
    action: str,
    *,
    actor: str | None = None,
    target_user: str | None = None,
    target: str | None = None,
    ip: str | None = None,
    **details,
) -> AuditEvent:
    event = AuditEvent(
        at=clock.now_ms(),
        actor_user_id=actor,
        action=action,
        target_user_id=target_user,
        target_id=target,
        ip=ip,
        details=details,
    )
    db.add(event)
    db.flush()
    return event


def list_events(db: Session, before_id: int | None, limit: int) -> list[AuditEvent]:
    """Newest first; pass the smallest id of the previous page as `before_id` for the next one."""
    query = select(AuditEvent).order_by(AuditEvent.id.desc()).limit(limit)
    if before_id is not None:
        query = query.where(AuditEvent.id < before_id)
    return list(db.scalars(query))


def event_out(e: AuditEvent) -> dict:
    return {
        "id": e.id,
        "at": e.at,
        "actor_user_id": e.actor_user_id,
        "action": e.action,
        "target_user_id": e.target_user_id,
        "target_id": e.target_id,
        "ip": e.ip,
        "details": e.details or {},
    }

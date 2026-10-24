"""Periodic housekeeping: expired sessions and invitations, delivered envelopes, old audit rows, orphaned
attachments."""

import logging

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.models import AuditEvent, Invitation, MessageEnvelope, SessionToken
from app.models import Session as AuthSession
from app.services.attachments import collect_garbage

log = logging.getLogger("familychat.maintenance")

DAY_MS = 86_400_000


def prune(db: Session, settings: Settings) -> dict[str, int]:
    now = clock.now_ms()
    # Keep dead sessions a few days: their token history is what detects refresh token reuse.
    dead = (AuthSession.expires_at < now - DAY_MS) | (AuthSession.revoked_at < now - 7 * DAY_MS)
    db.execute(delete(SessionToken).where(SessionToken.session_id.in_(select(AuthSession.id).where(dead))))
    sessions = db.execute(delete(AuthSession).where(dead)).rowcount
    # Tokens older than a full refresh lifetime are expired anyway; dropping them bounds the table for
    # long-lived sessions (such a token is simply "invalid" instead of triggering a revocation).
    db.execute(
        delete(SessionToken).where(
            SessionToken.issued_at < now - settings.refresh_token_days * DAY_MS,
            SessionToken.token_hash.not_in(select(AuthSession.refresh_token_hash)),
        )
    )
    invites = db.execute(
        delete(Invitation).where(
            (Invitation.expires_at < now - 7 * DAY_MS)
            | (Invitation.used_at < now - 30 * DAY_MS)
            | (Invitation.revoked_at < now - 30 * DAY_MS)
        )
    ).rowcount
    # A delivered envelope is ciphertext nobody needs again (the recipient has it). Undelivered ones stay: they
    # are the only copy a device that was offline for months can still fetch.
    envelopes = db.execute(
        delete(MessageEnvelope).where(
            MessageEnvelope.delivered_at < now - settings.envelope_retention_days * DAY_MS,
        )
    ).rowcount
    audit_events = db.execute(
        delete(AuditEvent).where(AuditEvent.at < now - settings.audit_retention_days * DAY_MS)
    ).rowcount
    db.commit()
    attachments = collect_garbage(db, settings)
    result = {
        "sessions": sessions,
        "invitations": invites,
        "envelopes": envelopes,
        "audit_events": audit_events,
        "attachments": attachments,
    }
    log.info("maintenance %s", result)
    return result

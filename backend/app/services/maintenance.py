"""Periodic housekeeping: expired sessions, invitations and reset codes, delivered envelopes, expired disappearing
messages, old audit rows, orphaned attachments and avatars."""

import logging

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.models import AuditEvent, Invitation, Message, MessageEnvelope, PasswordReset, SessionToken
from app.models import Session as AuthSession
from app.services.attachments import collect_garbage

log = logging.getLogger("familychat.maintenance")

DAY_MS = 86_400_000


def expire_messages(db: Session, now: int) -> int:
    """Delete messages whose server side expiry has passed, plus the edits and reactions that name them. The
    envelopes, attachment links and delivery receipts go with them (ON DELETE CASCADE); attachments nothing
    references any more are removed by the garbage collector that runs right after."""
    expired = select(Message.id).where(Message.expires_at.is_not(None), Message.expires_at <= now)
    derived = delete(Message).where(Message.target_message_id.in_(expired))
    count = db.execute(derived).rowcount
    count += db.execute(delete(Message).where(Message.expires_at.is_not(None), Message.expires_at <= now)).rowcount
    return count


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
    resets = db.execute(
        delete(PasswordReset).where(
            (PasswordReset.expires_at < now - 7 * DAY_MS)
            | (PasswordReset.used_at < now - 30 * DAY_MS)
            | (PasswordReset.revoked_at < now - 30 * DAY_MS)
        )
    ).rowcount
    messages = expire_messages(db, now)
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
        "password_resets": resets,
        "messages": messages,
        "envelopes": envelopes,
        "audit_events": audit_events,
        "attachments": attachments,
    }
    log.info("maintenance %s", result)
    return result

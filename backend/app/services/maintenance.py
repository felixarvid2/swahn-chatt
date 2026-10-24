"""Periodic housekeeping: expired sessions and invitations, orphaned attachments."""

import logging

from sqlalchemy import delete
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.models import Invitation
from app.models import Session as AuthSession
from app.services.attachments import collect_garbage

log = logging.getLogger("familychat.maintenance")

DAY_MS = 86_400_000


def prune(db: Session, settings: Settings) -> dict[str, int]:
    now = clock.now_ms()
    # Keep dead sessions a few days: their previous_token_hash is what detects refresh token reuse.
    sessions = db.execute(
        delete(AuthSession).where((AuthSession.expires_at < now - DAY_MS) | (AuthSession.revoked_at < now - 7 * DAY_MS))
    ).rowcount
    invites = db.execute(
        delete(Invitation).where(
            (Invitation.expires_at < now - 7 * DAY_MS)
            | (Invitation.used_at < now - 30 * DAY_MS)
            | (Invitation.revoked_at < now - 30 * DAY_MS)
        )
    ).rowcount
    db.commit()
    attachments = collect_garbage(db, settings)
    result = {"sessions": sessions, "invitations": invites, "attachments": attachments}
    log.info("maintenance %s", result)
    return result

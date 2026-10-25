"""Attachment storage on disk (random UUID names; client file names are never used) and garbage collection."""

import logging
import os
import re
import time
import uuid
from pathlib import Path

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.models import (
    Attachment,
    Conversation,
    ConversationMember,
    Message,
    MessageAttachment,
    UploadSession,
    User,
)

log = logging.getLogger("swahnchat.attachments")

AVATAR_TYPES = {"image/jpeg", "image/png", "image/webp"}
AVATAR_MAX_BYTES = 2 * 1024 * 1024
GC_AGE_MS = 24 * 3600 * 1000


def magic_matches(content_type: str, head: bytes) -> bool:
    if content_type == "image/jpeg":
        return head.startswith(b"\xff\xd8\xff")
    if content_type == "image/png":
        return head.startswith(b"\x89PNG\r\n\x1a\n")
    if content_type == "image/webp":
        return len(head) >= 12 and head[:4] == b"RIFF" and head[8:12] == b"WEBP"
    return False


def tmp_dir(settings: Settings) -> Path:
    d = settings.attachments_dir / ".tmp"
    d.mkdir(parents=True, exist_ok=True)
    return d


def new_storage_path(att_id: str) -> str:
    return f"{att_id[:2]}/{att_id}"


UUID_PATH_RE = re.compile(r"^[0-9a-f]{2}/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")


class InvalidStoragePath(ValueError):
    pass


def check_storage_path(storage_path: str, att_id: str | None = None) -> None:
    """The format new_storage_path() produces: `<first two id chars>/<id>`. Anything else is a tampered row."""
    m = UUID_PATH_RE.match(storage_path or "")
    if m is None or storage_path[:2] != m.group(1)[:2] or (att_id is not None and m.group(1) != att_id):
        raise InvalidStoragePath("attachment storage path has an unexpected format")


def full_path(settings: Settings, storage_path: str, att_id: str | None = None) -> Path:
    """Absolute file path for a stored attachment; refuses anything that could leave the attachments directory
    (a restored or hand-edited database must never make the server read or delete other files)."""
    check_storage_path(storage_path, att_id)
    base = settings.attachments_dir.resolve()
    path = (base / storage_path).resolve()
    if not path.is_relative_to(base):
        raise InvalidStoragePath("attachment storage path escapes the attachments directory")
    return path


def used_bytes(db: Session, user_id: str) -> int:
    """Quota in use: stored attachments plus the full size of the user's live resumable upload sessions (reserved
    when the session starts, so parallel uploads cannot overshoot the quota)."""
    stored = db.scalar(select(func.coalesce(func.sum(Attachment.size), 0)).where(Attachment.uploader_id == user_id))
    reserved = db.scalar(
        select(func.coalesce(func.sum(UploadSession.size), 0)).where(
            UploadSession.user_id == user_id, UploadSession.expires_at > clock.now_ms()
        )
    )
    return int(stored or 0) + int(reserved or 0)


def can_access(db: Session, user_id: str, att: Attachment) -> bool:
    if att.purpose == "avatar" or att.uploader_id == user_id:
        return True
    return (
        db.scalar(
            select(func.count())
            .select_from(MessageAttachment)
            .join(Message, Message.id == MessageAttachment.message_id)
            .join(
                ConversationMember,
                (ConversationMember.conversation_id == Message.conversation_id)
                & (ConversationMember.user_id == user_id),
            )
            .where(MessageAttachment.attachment_id == att.id, Message.deleted_at.is_(None))
        )
        > 0
    )


def new_id() -> str:
    return str(uuid.uuid4())


def collect_garbage(db: Session, settings: Settings) -> int:
    """Delete message attachments older than 24 h that no message references, and unused avatars."""
    cutoff = clock.now_ms() - GC_AGE_MS
    referenced_msg = select(MessageAttachment.attachment_id)
    used_avatars = (
        select(User.avatar_attachment_id)
        .where(User.avatar_attachment_id.is_not(None))
        .union(select(Conversation.avatar_attachment_id).where(Conversation.avatar_attachment_id.is_not(None)))
    )
    removed_users = select(User.id).where(User.disabled_at.is_not(None))
    victims = db.scalars(
        select(Attachment).where(
            # Accounts that were removed (by an admin or themselves) leave no orphans behind: their attachments that
            # no message references go at the next run, not after the 24 h grace period.
            (Attachment.created_at < cutoff) | Attachment.uploader_id.in_(removed_users),
            ((Attachment.purpose == "message") & Attachment.id.not_in(referenced_msg))
            | ((Attachment.purpose == "avatar") & Attachment.id.not_in(used_avatars)),
        )
    ).all()
    for att in victims:
        try:
            full_path(settings, att.storage_path, att.id).unlink(missing_ok=True)
        except InvalidStoragePath:
            log.error("attachment %s has an invalid storage path; row and file left untouched", att.id)
            continue
        except OSError:
            log.warning("could not delete attachment file")
            continue
        db.execute(delete(Attachment).where(Attachment.id == att.id))
    db.commit()
    # leftover temp files of interrupted uploads
    tdir = settings.attachments_dir / ".tmp"
    if tdir.is_dir():
        for f in tdir.iterdir():
            try:
                if time.time() - f.stat().st_mtime > 86_400:
                    f.unlink()
            except OSError:
                pass
    return len(victims)


def fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)

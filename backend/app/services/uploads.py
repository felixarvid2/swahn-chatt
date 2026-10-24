"""Resumable uploads for large message attachments: a session reserves quota for its full size, chunks are appended
to a temp file under the attachments directory, and the last chunk turns the file into an ordinary attachment."""

import hashlib
import logging
import os
import re
import time
from pathlib import Path

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.errors import ApiError, not_found
from app.models import UploadSession, User
from app.services.attachments import tmp_dir

log = logging.getLogger("familychat.uploads")

CHUNK_MAX_BYTES = 8 * 1024 * 1024
SESSION_TTL_MS = 24 * 3600 * 1000
MAX_ACTIVE_PER_USER = 10
ORPHAN_AGE_S = 3600
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def uploads_dir(settings: Settings) -> Path:
    d = tmp_dir(settings) / "uploads"
    d.mkdir(parents=True, exist_ok=True)
    return d


def part_path(settings: Settings, upload_id: str) -> Path:
    """Temp file of a session. The name comes from the (validated, server generated) id only: nothing the client
    sends ever becomes part of a path."""
    if not UUID_RE.match(upload_id):
        raise not_found("upload not found")
    base = uploads_dir(settings).resolve()
    path = (base / f"{upload_id}.part").resolve()
    if path.parent != base:
        raise not_found("upload not found")
    return path


def get_session(db: Session, upload_id: str, user_id: str) -> UploadSession:
    """The caller's live session. Other users' sessions, expired ones and unknown ids are all the same 404."""
    row = db.get(UploadSession, upload_id) if UUID_RE.match(upload_id) else None
    if row is None or row.user_id != user_id or row.expires_at <= clock.now_ms():
        raise not_found("upload not found")
    return row


def active_count(db: Session, user_id: str) -> int:
    return (
        db.scalar(
            select(func.count())
            .select_from(UploadSession)
            .where(UploadSession.user_id == user_id, UploadSession.expires_at > clock.now_ms())
        )
        or 0
    )


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def drop_session(db: Session, settings: Settings, upload_id: str) -> None:
    db.execute(delete(UploadSession).where(UploadSession.id == upload_id))
    db.commit()
    try:
        part_path(settings, upload_id).unlink(missing_ok=True)
    except (ApiError, OSError):
        log.warning("could not remove an upload temp file")


def collect_expired(db: Session, settings: Settings) -> int:
    """Hourly: remove expired sessions (and those of removed accounts) with their temp files, which releases their
    reserved quota, then temp files that belong to no session at all."""
    now = clock.now_ms()
    gone = select(User.id).where(User.disabled_at.is_not(None))
    ids = list(
        db.scalars(select(UploadSession.id).where((UploadSession.expires_at <= now) | UploadSession.user_id.in_(gone)))
    )
    for upload_id in ids:
        drop_session(db, settings, upload_id)
    live = set(db.scalars(select(UploadSession.id)))
    for f in uploads_dir(settings).iterdir():
        try:
            if f.suffix == ".part" and f.stem not in live and time.time() - f.stat().st_mtime > ORPHAN_AGE_S:
                f.unlink()
        except OSError:
            pass
    return len(ids)


def fsync_file(f) -> None:
    f.flush()
    os.fsync(f.fileno())

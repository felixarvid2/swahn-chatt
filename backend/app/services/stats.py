"""Server figures for GET /admin/stats and the /readyz disk check."""

import os
import shutil
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import Settings
from app.models import Attachment, Conversation, Device, Message, MessageEnvelope, User


def free_disk_bytes(path: Path) -> int:
    return shutil.disk_usage(path).free


def dir_bytes(path: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:  # removed while walking
                pass
    return total


def db_file_bytes(settings: Settings) -> int | None:
    """Size of the SQLite database including its WAL; None for other databases."""
    if not settings.is_sqlite:
        return None
    base = settings.sqlite_path
    total = 0
    for suffix in ("", "-wal"):
        try:
            total += os.path.getsize(f"{base}{suffix}")
        except OSError:
            pass
    return total


def counts(db: Session) -> dict[str, int]:
    def n(query) -> int:
        return db.scalar(query) or 0

    active_device = Device.revoked_at.is_(None)
    return {
        "users": n(select(func.count()).select_from(User).where(User.disabled_at.is_(None))),
        "active_devices": n(select(func.count()).select_from(Device).where(active_device)),
        "conversations": n(select(func.count()).select_from(Conversation)),
        "messages": n(select(func.count()).select_from(Message)),
        "undelivered_envelopes": n(
            select(func.count())
            .select_from(MessageEnvelope)
            .join(Device, Device.id == MessageEnvelope.device_id)
            .where(MessageEnvelope.delivered_at.is_(None), active_device)
        ),
        "attachments": n(select(func.count()).select_from(Attachment)),
    }

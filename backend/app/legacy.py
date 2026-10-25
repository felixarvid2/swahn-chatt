"""Adopt data written under the old name: before the rename to Swahn Chat the SQLite file was `familychat.db`."""

import logging
import os

from app.config import Settings

log = logging.getLogger("swahnchat.legacy")

LEGACY_DB_NAME = "familychat.db"
DB_NAME = "swahnchat.db"
_SUFFIXES = ("-wal", "-shm")


def adopt_legacy_database(settings: Settings) -> bool:
    """Rename `<data_dir>/familychat.db` (and its -wal/-shm files) to `swahnchat.db` when the new file does not
    exist yet. Only for the default SQLite location. The sidecar files go first and the main file last, so a crash
    in between leaves the old name in place and the next start finishes the job. Returns True when it renamed."""
    if not settings.is_sqlite or settings.sqlite_path != (settings.data_dir / DB_NAME).resolve():
        return False
    new = settings.data_dir / DB_NAME
    old = settings.data_dir / LEGACY_DB_NAME
    if new.exists() or not old.exists():
        return False
    for suffix in _SUFFIXES:
        side = old.with_name(old.name + suffix)
        if side.exists():
            os.replace(side, new.with_name(new.name + suffix))
    os.replace(old, new)
    log.info("renamed the old database %s to %s (Swahn Chat rename)", old, new)
    return True


if __name__ == "__main__":
    adopt_legacy_database(Settings())

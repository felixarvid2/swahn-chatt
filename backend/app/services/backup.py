"""Backups: one .tar.gz with a consistent SQLite copy (online backup API), attachments and a checksum manifest."""

import hashlib
import io
import json
import os
import re
import shutil
import sqlite3
import tarfile
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path

from app.config import Settings

FORMAT = 1
DB_NAME = "familychat.db"
ATT_RE = re.compile(r"^attachments/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class BackupError(RuntimeError):
    pass


def _sha256_file(path: Path) -> tuple[str, int]:
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def _counts(db_path: Path) -> dict[str, int]:
    con = sqlite3.connect(db_path)
    try:
        out = {}
        for name, table in [
            ("users", "users"),
            ("devices", "devices"),
            ("conversations", "conversations"),
            ("messages", "messages"),
            ("attachments", "attachments"),
        ]:
            out[name] = con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]  # noqa: S608 (fixed names)
        return out
    finally:
        con.close()


def _attachment_files(att_dir: Path) -> list[Path]:
    if not att_dir.is_dir():
        return []
    return sorted(p for p in att_dir.rglob("*") if p.is_file() and ".tmp" not in p.relative_to(att_dir).parts)


def create_backup(settings: Settings, out_dir: Path) -> Path:
    if not settings.is_sqlite:
        raise BackupError("backup supports SQLite only; use pg_dump for PostgreSQL")
    db_path = settings.sqlite_path
    if not db_path.is_file():
        raise BackupError(f"database not found: {db_path}")
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    final = out_dir / f"familychat-backup-{stamp}.tar.gz"
    n = 1
    while final.exists():
        final = out_dir / f"familychat-backup-{stamp}-{n}.tar.gz"
        n += 1
    partial = final.with_name(final.name + ".partial")
    with tempfile.TemporaryDirectory(prefix="fc-backup-", dir=out_dir) as tmp:
        copy = Path(tmp) / DB_NAME
        src = sqlite3.connect(db_path, timeout=30)
        dst = sqlite3.connect(copy)
        try:
            src.backup(dst)  # consistent snapshot even while the server writes
            dst.execute("PRAGMA journal_mode=DELETE")
        finally:
            dst.close()
            src.close()
        db_sha, db_size = _sha256_file(copy)
        counts = _counts(copy)
        entries = []
        fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as raw, tarfile.open(fileobj=raw, mode="w:gz") as tar:
            tar.add(copy, arcname=DB_NAME, filter=_plain)
            for f in _attachment_files(settings.attachments_dir):
                rel = "attachments/" + f.relative_to(settings.attachments_dir).as_posix()
                try:
                    sha, size = _sha256_file(f)
                    info = tarfile.TarInfo(rel)
                    info.size = size
                    info.mtime = int(f.stat().st_mtime)
                    info.mode = 0o600
                    with open(f, "rb") as fh:
                        tar.addfile(info, fh)
                except FileNotFoundError:
                    continue  # removed by the garbage collector meanwhile
                entries.append({"path": rel, "size": size, "sha256": sha})
            counts["attachment_files"] = len(entries)
            manifest = {
                "format": FORMAT,
                "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
                "database": {"path": DB_NAME, "size": db_size, "sha256": db_sha},
                "counts": counts,
                "attachments": entries,
            }
            data = json.dumps(manifest, indent=2).encode()
            info = tarfile.TarInfo("manifest.json")
            info.size = len(data)
            info.mtime = int(time.time())
            info.mode = 0o600
            tar.addfile(info, io.BytesIO(data))
            raw.flush()
            os.fsync(raw.fileno())
    os.replace(partial, final)
    return final


def _plain(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.mode = 0o600
    return info


def _has_data(settings: Settings) -> bool:
    return settings.sqlite_path.exists() or bool(_attachment_files(settings.attachments_dir))


def restore_backup(settings: Settings, archive: Path, force: bool = False) -> dict:
    if not settings.is_sqlite:
        raise BackupError("restore supports SQLite only")
    if not archive.is_file():
        raise BackupError(f"archive not found: {archive}")
    if _has_data(settings) and not force:
        raise BackupError("data already exists in the data directory; use --force to replace it")
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".restore-", dir=settings.data_dir))
    try:
        manifest = _extract_verified(archive, staging)
        stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
        db_path = settings.sqlite_path
        aside = None
        if _has_data(settings):
            aside = settings.data_dir / f"pre-restore-{stamp}"
            aside.mkdir()
            for suffix in ("", "-wal", "-shm"):
                p = db_path.with_name(db_path.name + suffix)
                if p.exists():
                    shutil.move(str(p), aside / p.name)
            if settings.attachments_dir.exists():
                shutil.move(str(settings.attachments_dir), aside / "attachments")
        db_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staging / DB_NAME), db_path)
        settings.attachments_dir.parent.mkdir(parents=True, exist_ok=True)
        if (staging / "attachments").is_dir():
            shutil.move(str(staging / "attachments"), settings.attachments_dir)
        else:
            settings.attachments_dir.mkdir(parents=True, exist_ok=True)
        return {"counts": manifest["counts"], "previous_data": str(aside) if aside else None}
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _extract_verified(archive: Path, staging: Path) -> dict:
    """Extract only known file names into `staging`, hashing while writing; then compare with the manifest."""
    seen: dict[str, tuple[str, int]] = {}
    try:
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                name = member.name
                if not member.isfile():
                    raise BackupError(f"unexpected non-file entry in archive: {name!r}")
                if name not in ("manifest.json", DB_NAME) and not ATT_RE.match(name):
                    raise BackupError(f"unexpected entry in archive: {name!r}")
                target = staging / name
                target.parent.mkdir(parents=True, exist_ok=True)
                src = tar.extractfile(member)
                h = hashlib.sha256()
                size = 0
                with open(target, "wb") as out:
                    while chunk := src.read(1024 * 1024):
                        h.update(chunk)
                        size += len(chunk)
                        out.write(chunk)
                seen[name] = (h.hexdigest(), size)
    except (tarfile.TarError, OSError, EOFError) as exc:
        raise BackupError(f"archive is unreadable or corrupted: {exc}") from exc
    if "manifest.json" not in seen or DB_NAME not in seen:
        raise BackupError("archive has no manifest or database")
    try:
        manifest = json.loads((staging / "manifest.json").read_text())
        expected = {manifest["database"]["path"]: manifest["database"]}
        expected.update({e["path"]: e for e in manifest["attachments"]})
    except (ValueError, KeyError, TypeError) as exc:
        raise BackupError("manifest is invalid") from exc
    if manifest.get("format") != FORMAT:
        raise BackupError(f"unsupported backup format {manifest.get('format')!r}")
    actual = {k: v for k, v in seen.items() if k != "manifest.json"}
    if set(expected) != set(actual):
        raise BackupError("archive contents do not match the manifest")
    for path, (sha, size) in actual.items():
        if expected[path]["sha256"] != sha or expected[path]["size"] != size:
            raise BackupError(f"checksum mismatch for {path}")
    con = sqlite3.connect(staging / DB_NAME)
    try:
        if con.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise BackupError("restored database failed its integrity check")
    except sqlite3.DatabaseError as exc:
        raise BackupError(f"restored database is not a valid SQLite file: {exc}") from exc
    finally:
        con.close()
    return manifest

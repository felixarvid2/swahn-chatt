"""Attachments: streamed upload (never fully in memory), ranged download."""

import hashlib
import logging
import os
import re
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.deps import Principal, get_principal, get_read_db, get_settings_dep
from app.errors import ApiError, not_found
from app.models import Attachment
from app.schemas import AttachmentOut
from app.services import attachments as svc

log = logging.getLogger("familychat.attachments")

router = APIRouter(prefix="/attachments", tags=["attachments"])

SHA_RE = re.compile(r"^[0-9a-fA-F]{64}$")


def _reject(code: int, name: str, message: str) -> ApiError:
    return ApiError(code, name, message)


@router.post("", status_code=201, response_model=AttachmentOut)
async def upload(
    request: Request,
    purpose: Literal["message", "avatar"] = Query(...),
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
):
    content_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
    if purpose == "message":
        if content_type != "application/octet-stream":
            raise _reject(415, "unsupported_media_type", "message attachments must be application/octet-stream")
        limit = settings.max_attachment_bytes
    else:
        if content_type not in svc.AVATAR_TYPES:
            raise _reject(415, "unsupported_media_type", "avatar must be image/jpeg, image/png or image/webp")
        limit = svc.AVATAR_MAX_BYTES
    expected = request.headers.get("x-content-sha256")
    if expected is not None and not SHA_RE.match(expected.strip()):
        raise ApiError(422, "invalid_request", "X-Content-SHA256 must be 64 hex characters")
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise _reject(413, "payload_too_large", f"attachment larger than {limit // (1024 * 1024)} MiB")

    quota_left = settings.user_quota_bytes - await run_in_threadpool(_used, request, p.user_id)
    if declared and declared.isdigit() and int(declared) > quota_left:
        raise _reject(413, "quota_exceeded", "storage quota exceeded")

    att_id = svc.new_id()
    tmp = svc.tmp_dir(settings) / f"{att_id}.part"
    sha = hashlib.sha256()
    size = 0
    head = b""
    try:
        with open(tmp, "wb") as f:
            async for chunk in request.stream():
                if not chunk:
                    continue
                size += len(chunk)
                if size > limit:
                    raise _reject(413, "payload_too_large", f"attachment larger than {limit // (1024 * 1024)} MiB")
                if size > quota_left:
                    raise _reject(413, "quota_exceeded", "storage quota exceeded")
                if len(head) < 16:
                    head = (head + chunk)[:16]
                sha.update(chunk)
                await run_in_threadpool(f.write, chunk)
            await run_in_threadpool(_sync_file, f)
        if size == 0:
            raise ApiError(422, "invalid_request", "empty body")
        if purpose == "avatar" and not svc.magic_matches(content_type, head):
            raise _reject(415, "unsupported_media_type", "file content does not match its image type")
        digest = sha.hexdigest()
        if expected is not None and expected.strip().lower() != digest:
            raise ApiError(422, "checksum_mismatch", "uploaded bytes do not match X-Content-SHA256")
        att = await run_in_threadpool(_finish, request, settings, p, att_id, purpose, content_type, size, digest, tmp)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    return att


def _used(request: Request, user_id: str) -> int:
    with request.app.state.read_session_factory() as db:
        return svc.used_bytes(db, user_id)


def _sync_file(f) -> None:
    f.flush()
    os.fsync(f.fileno())


def _finish(request, settings, p, att_id, purpose, content_type, size, digest, tmp) -> dict:
    final_rel = svc.new_storage_path(att_id)
    final = svc.full_path(settings, final_rel, att_id)
    final.parent.mkdir(parents=True, exist_ok=True)
    with request.app.state.session_factory() as db:
        # Re-check the quota inside the write transaction so parallel uploads cannot overshoot it.
        if svc.used_bytes(db, p.user_id) + size > settings.user_quota_bytes:
            raise ApiError(413, "quota_exceeded", "storage quota exceeded")
        os.replace(tmp, final)
        att = Attachment(
            id=att_id,
            uploader_id=p.user_id,
            purpose=purpose,
            content_type="application/octet-stream" if purpose == "message" else content_type,
            size=size,
            sha256=digest,
            storage_path=final_rel,
            created_at=clock.now_ms(),
        )
        db.add(att)
        try:
            db.commit()
        except BaseException:
            final.unlink(missing_ok=True)
            raise
        return {
            "id": att.id,
            "purpose": att.purpose,
            "content_type": att.content_type,
            "size": att.size,
            "sha256": att.sha256,
            "created_at": att.created_at,
        }


@router.get("/{attachment_id}")
def download(
    attachment_id: str,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_read_db),
):
    att = db.get(Attachment, attachment_id)
    if att is None or not svc.can_access(db, p.user_id, att):
        raise not_found("attachment not found")
    try:
        path = svc.full_path(settings, att.storage_path, att.id)
    except svc.InvalidStoragePath:
        log.error("attachment %s has an invalid storage path", att.id)
        raise not_found("attachment not found") from None
    if not path.is_file():
        raise not_found("attachment not found")
    return FileResponse(
        path,
        media_type=att.content_type,
        headers={
            "ETag": f'"{att.sha256}"',
            "Cache-Control": "private, no-store",
            "X-Content-Type-Options": "nosniff",
        },
    )

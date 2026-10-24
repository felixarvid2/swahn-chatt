"""Attachments: streamed upload (never fully in memory), ranged download."""

import contextlib
import hashlib
import logging
import os
import re
from typing import Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse
from sqlalchemy import update
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.deps import Principal, get_db, get_limiter, get_principal, get_read_db, get_settings_dep
from app.errors import ApiError, conflict, error_responses, not_found
from app.models import Attachment, UploadSession
from app.ratelimit import RateLimiter
from app.schemas import AttachmentOut, UploadCreate, UploadProgressOut, UploadSessionOut
from app.services import attachments as svc
from app.services import uploads

log = logging.getLogger("familychat.attachments")

router = APIRouter(prefix="/attachments", tags=["attachments"], responses=error_responses(401))

SHA_RE = re.compile(r"^[0-9a-fA-F]{64}$")


def _reject(code: int, name: str, message: str) -> ApiError:
    return ApiError(code, name, message)


@router.post(
    "",
    status_code=201,
    response_model=AttachmentOut,
    summary="Upload an attachment",
    responses=error_responses(413, 415, 422),
)
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


# ---------------------------------------------------------------- resumable uploads


def _session_out(row: UploadSession) -> dict:
    return {
        "upload_id": row.id,
        "offset": row.offset,
        "size": row.size,
        "expires_at": row.expires_at,
        "max_chunk_bytes": uploads.CHUNK_MAX_BYTES,
    }


def _progress_headers(row_offset: int, size: int | None = None) -> dict[str, str]:
    headers = {"Upload-Offset": str(row_offset)}
    if size is not None:
        headers["Upload-Length"] = str(size)
    return headers


@router.post(
    "/uploads",
    status_code=201,
    response_model=UploadSessionOut,
    summary="Start a resumable upload",
    responses=error_responses(413, 422, 429),
)
def create_upload(
    body: UploadCreate,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    db: Session = Depends(get_db),
):
    """Reserves quota for `size` bytes at once and returns the session. Send the bytes with `PATCH`."""
    limiter.check(f"upload-start:{p.user_id}", 30)
    if body.size > settings.max_attachment_bytes:
        raise _reject(413, "payload_too_large", f"attachment larger than {settings.max_attachment_mb} MiB")
    if uploads.active_count(db, p.user_id) >= uploads.MAX_ACTIVE_PER_USER:
        raise ApiError(429, "too_many_uploads", "too many unfinished uploads; finish or wait for them to expire")
    if svc.used_bytes(db, p.user_id) + body.size > settings.user_quota_bytes:
        raise _reject(413, "quota_exceeded", "storage quota exceeded")
    now = clock.now_ms()
    row = UploadSession(
        id=svc.new_id(),
        user_id=p.user_id,
        purpose=body.purpose,
        size=body.size,
        sha256=body.sha256.lower() if body.sha256 else None,
        offset=0,
        created_at=now,
        expires_at=now + uploads.SESSION_TTL_MS,
    )
    path = uploads.part_path(settings, row.id)
    path.touch(mode=0o600)
    db.add(row)
    try:
        db.commit()
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    return _session_out(row)


@router.get(
    "/uploads/{upload_id}",
    response_model=UploadSessionOut,
    summary="Get the state of a resumable upload",
    responses=error_responses(404),
)
def get_upload(upload_id: str, p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    """The current `offset`: where to continue after an interruption. Also sent as the `Upload-Offset` header."""
    row = uploads.get_session(db, upload_id, p.user_id)
    return JSONResponse(_session_out(row), headers=_progress_headers(row.offset, row.size))


@router.head("/uploads/{upload_id}", include_in_schema=False)
def head_upload(upload_id: str, p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    row = uploads.get_session(db, upload_id, p.user_id)
    return Response(status_code=200, headers=_progress_headers(row.offset, row.size))


def _load_session(request: Request, upload_id: str, user_id: str) -> dict:
    with request.app.state.read_session_factory() as db:
        row = uploads.get_session(db, upload_id, user_id)
        return {"offset": row.offset, "size": row.size, "sha256": row.sha256}


def _commit_offset(request: Request, upload_id: str, old: int, new: int) -> bool:
    with request.app.state.session_factory() as db:
        done = db.execute(
            update(UploadSession)
            .where(
                UploadSession.id == upload_id, UploadSession.offset == old, UploadSession.expires_at > clock.now_ms()
            )
            .values(offset=new)
        ).rowcount
        db.commit()
        return done == 1


def _complete(request: Request, settings: Settings, p: Principal, upload_id: str, size: int, sha: str | None) -> dict:
    """All bytes are in: verify, then turn the temp file into an attachment (and drop the session) in one go."""
    part = uploads.part_path(settings, upload_id)
    if part.stat().st_size != size:
        log.error("upload %s: temp file size differs from the session", upload_id)
        with request.app.state.session_factory() as db:
            uploads.drop_session(db, settings, upload_id)
        raise conflict("the stored upload is inconsistent; start a new one", "upload_corrupt")
    digest = uploads.sha256_file(part)
    if sha is not None and sha != digest:
        with request.app.state.session_factory() as db:
            uploads.drop_session(db, settings, upload_id)  # the bytes are wrong and cannot be rewritten
        raise ApiError(422, "checksum_mismatch", "uploaded bytes do not match the announced sha256; start again")
    final_rel = svc.new_storage_path(upload_id)
    final = svc.full_path(settings, final_rel, upload_id)
    final.parent.mkdir(parents=True, exist_ok=True)
    with request.app.state.session_factory() as db:
        row = db.get(UploadSession, upload_id)
        if row is None or row.user_id != p.user_id:
            raise not_found("upload not found")
        os.replace(part, final)
        att = Attachment(
            id=upload_id,
            uploader_id=p.user_id,
            purpose="message",
            content_type="application/octet-stream",
            size=size,
            sha256=digest,
            storage_path=final_rel,
            created_at=clock.now_ms(),
        )
        db.add(att)
        db.delete(row)  # its reservation turns into the attachment's size
        try:
            db.commit()
        except BaseException:
            os.replace(final, part)
            raise
        return {
            "id": att.id,
            "purpose": att.purpose,
            "content_type": att.content_type,
            "size": att.size,
            "sha256": att.sha256,
            "created_at": att.created_at,
        }


def _prepare_part(settings: Settings, upload_id: str, offset: int):
    """Open the temp file for appending at `offset`; bytes beyond it (an interrupted chunk) are discarded."""
    path = uploads.part_path(settings, upload_id)
    if not path.exists():
        if offset != 0:
            raise not_found("upload not found")
        path.touch(mode=0o600)
    f = open(path, "r+b")  # noqa: SIM115 - closed by the caller
    f.truncate(offset)
    f.seek(offset)
    return f


@router.patch(
    "/uploads/{upload_id}",
    response_model=UploadProgressOut,
    summary="Send a chunk of a resumable upload",
    responses={
        **error_responses(404, 409, 413, 415, 422),
        201: {"model": AttachmentOut, "description": "The last chunk arrived: the finished attachment."},
    },
)
async def patch_upload(upload_id: str, request: Request, p: Principal = Depends(get_principal)):
    """Body: up to 8 MiB of raw bytes (`application/octet-stream`); header `Upload-Offset` must equal the session's
    current offset (`409 offset_mismatch` carries the right one). Answers `200 {"offset"}`, or `201` with the
    attachment once the last byte is in."""
    settings: Settings = request.app.state.settings
    if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/octet-stream":
        raise _reject(415, "unsupported_media_type", "chunks must be application/octet-stream")
    raw = request.headers.get("upload-offset", "").strip()
    if not raw.isdigit() or len(raw) > 18:
        raise ApiError(422, "invalid_request", "Upload-Offset header (a non-negative integer) is required")
    claimed = int(raw)
    busy: set[str] = request.app.state.uploads_busy
    if upload_id in busy:
        raise conflict("another chunk of this upload is being written", "upload_busy")
    busy.add(upload_id)
    try:
        state = await run_in_threadpool(_load_session, request, upload_id, p.user_id)
        offset, size = state["offset"], state["size"]
        if claimed != offset:
            raise ApiError(
                409,
                "offset_mismatch",
                "Upload-Offset does not match the server's offset",
                {"offset": offset},
                _progress_headers(offset),
            )
        room = min(uploads.CHUNK_MAX_BYTES, size - offset)
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > room:
            raise _reject(413, "payload_too_large", f"a chunk may hold at most {room} bytes here")
        written = 0
        f = await run_in_threadpool(_prepare_part, settings, upload_id, offset)
        try:
            async for chunk in request.stream():
                if not chunk:
                    continue
                written += len(chunk)
                if written > room:
                    raise _reject(413, "payload_too_large", f"a chunk may hold at most {room} bytes here")
                await run_in_threadpool(f.write, chunk)
            if written == 0:
                raise ApiError(422, "invalid_request", "empty chunk")
            await run_in_threadpool(uploads.fsync_file, f)
        except BaseException:
            with contextlib.suppress(OSError):
                f.truncate(offset)  # an interrupted chunk leaves no trace; the client resumes at `offset`
            raise
        finally:
            f.close()
        new_offset = offset + written
        if not await run_in_threadpool(_commit_offset, request, upload_id, offset, new_offset):
            raise conflict("the upload changed or expired meanwhile", "offset_mismatch")
        if new_offset < size:
            return JSONResponse({"offset": new_offset}, headers=_progress_headers(new_offset, size))
        att = await run_in_threadpool(_complete, request, settings, p, upload_id, size, state["sha256"])
        return JSONResponse(att, status_code=201)
    finally:
        busy.discard(upload_id)


@router.get(
    "/{attachment_id}",
    response_class=FileResponse,
    response_model=None,
    summary="Download an attachment",
    responses={
        **error_responses(404),
        200: {"content": {"application/octet-stream": {}}, "description": "The stored bytes (`Range` supported)."},
        206: {"content": {"application/octet-stream": {}}, "description": "Partial content for a `Range` request."},
        416: {"description": "Range not satisfiable (`range_not_satisfiable`)."},
    },
)
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

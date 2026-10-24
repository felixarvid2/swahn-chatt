import time
import uuid

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app import __version__, clock
from app.config import Settings
from app.deps import Principal, get_db, get_ip, get_read_db, get_settings_dep, require_admin
from app.errors import conflict, not_found
from app.migrate import code_head, db_revision
from app.models import Attachment, Device, Invitation, User
from app.schemas import (
    AdminStats,
    AdminUserOut,
    AuditEventOut,
    InvitationCreate,
    InvitationCreated,
    InvitationOut,
)
from app.security import hash_invite_code, new_invite_code
from app.services import accounts, audit, stats

router = APIRouter(prefix="/admin", tags=["admin"])


def create_invitation(
    db: Session, created_by: str | None, note: str | None, hours: int, is_admin: bool, ip: str | None = None
) -> tuple[Invitation, str]:
    code = new_invite_code()
    now = clock.now_ms()
    inv = Invitation(
        id=str(uuid.uuid4()),
        code_hash=hash_invite_code(code),
        note=note,
        is_admin=is_admin,
        created_by=created_by,
        created_at=now,
        expires_at=now + hours * 3_600_000,
    )
    db.add(inv)
    db.flush()
    audit.record(db, "invitation_created", actor=created_by, target=inv.id, ip=ip, is_admin=is_admin, hours=hours)
    return inv, code


@router.post("/invitations", status_code=201, response_model=InvitationCreated)
def post_invitation(
    body: InvitationCreate,
    p: Principal = Depends(require_admin),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    inv, code = create_invitation(db, p.user_id, body.note, body.expires_in_hours, body.is_admin, ip)
    db.commit()
    return {
        "id": inv.id,
        "code": code,
        "note": inv.note,
        "is_admin": inv.is_admin,
        "expires_at": inv.expires_at,
        "created_at": inv.created_at,
    }


@router.get("/invitations", response_model=list[InvitationOut])
def list_invitations(_: Principal = Depends(require_admin), db: Session = Depends(get_read_db)):
    rows = db.scalars(select(Invitation).order_by(Invitation.created_at.desc())).all()
    return [
        {
            "id": i.id,
            "note": i.note,
            "is_admin": i.is_admin,
            "expires_at": i.expires_at,
            "created_at": i.created_at,
            "used_at": i.used_at,
            "used_by": i.used_by,
            "revoked_at": i.revoked_at,
        }
        for i in rows
    ]


@router.delete("/invitations/{invitation_id}", status_code=204)
def revoke_invitation(
    invitation_id: str,
    p: Principal = Depends(require_admin),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    inv = db.get(Invitation, invitation_id)
    if inv is None:
        raise not_found("invitation not found")
    if inv.revoked_at is None:
        inv.revoked_at = clock.now_ms()
        audit.record(db, "invitation_revoked", actor=p.user_id, target=inv.id, ip=ip)
        db.commit()
    return Response(status_code=204)


@router.delete("/users/{user_id}", status_code=204)
def remove_user(
    user_id: str,
    p: Principal = Depends(require_admin),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    if user_id == p.user_id:
        raise conflict("administrators cannot remove themselves")
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    accounts.disable_user(db, user)
    audit.record(db, "user_removed", actor=p.user_id, target_user=user.id, ip=ip, was_admin=user.is_admin)
    db.commit()
    return Response(status_code=204)


@router.delete("/users/{user_id}/devices/{device_id}", status_code=204)
def revoke_user_device(
    user_id: str,
    device_id: str,
    p: Principal = Depends(require_admin),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    """Lost phone: same effects as the owner revoking the device (keys deleted, sessions revoked, socket kicked)."""
    device = db.get(Device, device_id)
    if device is None or device.user_id != user_id or device.revoked_at is not None:
        raise not_found("device not found")
    if device_id == p.device_id:
        raise conflict("administrators cannot revoke the device they are using; log out instead")
    accounts.revoke_device(db, device_id)
    audit.record(db, "device_revoked", actor=p.user_id, target_user=user_id, target=device_id, ip=ip, by="admin")
    db.commit()
    return Response(status_code=204)


@router.get("/users", response_model=list[AdminUserOut])
def list_users(_: Principal = Depends(require_admin), db: Session = Depends(get_read_db)):
    devices = (
        select(
            Device.user_id.label("user_id"),
            func.max(Device.last_seen_at).label("last_seen_at"),
            func.sum(case((Device.revoked_at.is_(None), 1), else_=0)).label("active"),
        )
        .group_by(Device.user_id)
        .subquery()
    )
    files = (
        select(Attachment.uploader_id.label("user_id"), func.sum(Attachment.size).label("bytes"))
        .group_by(Attachment.uploader_id)
        .subquery()
    )
    rows = db.execute(
        select(User, devices.c.last_seen_at, devices.c.active, files.c.bytes)
        .outerjoin(devices, devices.c.user_id == User.id)
        .outerjoin(files, files.c.user_id == User.id)
        .order_by(User.created_at, User.id)
    ).all()
    return [
        {
            "id": u.id,
            "username": u.username,
            "display_name": u.display_name,
            "is_admin": u.is_admin,
            "created_at": u.created_at,
            "disabled_at": u.disabled_at,
            "last_seen_at": last_seen,
            "active_devices": int(active or 0),
            "attachment_bytes": int(size or 0),
        }
        for u, last_seen, active, size in rows
    ]


@router.get("/stats", response_model=AdminStats)
def get_stats(
    request: Request,
    _: Principal = Depends(require_admin),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_read_db),
):
    return {
        **stats.counts(db),
        "db_bytes": stats.db_file_bytes(settings),
        "attachments_bytes": stats.dir_bytes(settings.attachments_dir),
        "disk_free_bytes": stats.free_disk_bytes(settings.data_dir),
        "version": __version__,
        "migration": {"head": code_head(), "current": db_revision(request.app.state.engine)},
        "uptime_seconds": int(time.monotonic() - request.app.state.started_at),
    }


@router.get("/audit", response_model=list[AuditEventOut])
def get_audit(
    before_id: int | None = Query(None, ge=1),
    limit: int = Query(50, ge=1, le=200),
    _: Principal = Depends(require_admin),
    db: Session = Depends(get_read_db),
):
    """Newest first. Page backwards with `before_id` = the smallest `id` of the previous page."""
    return [audit.event_out(e) for e in audit.list_events(db, before_id, limit)]

import time
import uuid

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app import __version__, clock
from app.config import Settings
from app.deps import (
    Principal,
    get_alerts,
    get_db,
    get_ip,
    get_limiter,
    get_read_db,
    get_settings_dep,
    require_admin,
    require_admin_write,
)
from app.errors import ApiError, conflict, error_responses, invalid, not_found
from app.migrate import code_head, db_revision
from app.models import Attachment, Device, Invitation, User
from app.ratelimit import RateLimiter
from app.schemas import (
    AdminStats,
    AdminUserOut,
    AdminUserUpdate,
    AuditEventOut,
    InvitationCreate,
    InvitationCreated,
    InvitationOut,
    PasswordResetCreated,
    SelfDelete,
)
from app.security import hash_invite_code, new_invite_code, verify_password
from app.services import accounts, audit, dbmaint, lockout, password_reset, stats
from app.services.alerts import AdminAlerts

router = APIRouter(prefix="/admin", tags=["admin"], responses=error_responses(401, 403))


def create_invitation(
    db: Session,
    created_by: str | None,
    note: str | None,
    hours: int,
    is_admin: bool,
    ip: str | None = None,
    max_uses: int = 1,
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
        max_uses=max_uses,
        uses=0,
    )
    db.add(inv)
    db.flush()
    audit.record(
        db,
        "invitation_created",
        actor=created_by,
        target=inv.id,
        ip=ip,
        is_admin=is_admin,
        hours=hours,
        max_uses=max_uses,
    )
    return inv, code


@router.post(
    "/invitations",
    status_code=201,
    response_model=InvitationCreated,
    summary="Create an invitation",
    responses=error_responses(413, 422),
)
def post_invitation(
    body: InvitationCreate,
    p: Principal = Depends(require_admin_write),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    if body.is_admin and body.max_uses > 1:
        raise invalid("administrator invitations are single use (max_uses must be 1)")
    inv, code = create_invitation(db, p.user_id, body.note, body.expires_in_hours, body.is_admin, ip, body.max_uses)
    db.commit()
    return {
        "id": inv.id,
        "code": code,
        "note": inv.note,
        "is_admin": inv.is_admin,
        "expires_at": inv.expires_at,
        "created_at": inv.created_at,
        "max_uses": inv.max_uses,
    }


@router.get("/invitations", response_model=list[InvitationOut], summary="List invitations")
def list_invitations(_: Principal = Depends(require_admin), db: Session = Depends(get_read_db)):
    rows = db.scalars(select(Invitation).order_by(Invitation.created_at.desc())).all()
    now = clock.now_ms()
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
            "max_uses": i.max_uses,
            "uses": i.uses,
            "remaining": 0 if i.revoked_at is not None or i.expires_at <= now else max(i.max_uses - i.uses, 0),
        }
        for i in rows
    ]


@router.delete(
    "/invitations/{invitation_id}", status_code=204, summary="Revoke an invitation", responses=error_responses(404)
)
def revoke_invitation(
    invitation_id: str,
    p: Principal = Depends(require_admin_write),
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


def confirm_admin_password(
    p: Principal,
    password: str,
    action: str,
    target_user: str,
    settings: Settings,
    limiter: RateLimiter,
    alerts: AdminAlerts,
    ip: str,
    rdb: Session,
    db: Session,
) -> None:
    """Re-authenticate the calling administrator (same checks as DELETE /users/me): an administrator's session alone
    must not be enough to take over or remove an account."""
    limiter.check(f"admin-confirm:{p.user_id}", 20)
    lockout.ensure_not_locked(limiter, settings, p.username)
    current = rdb.scalar(select(User.password_hash).where(User.id == p.user_id))
    rdb.rollback()  # no transaction while Argon2 runs
    if current is None or not verify_password(settings, current, password):
        if lockout.record_failure(db, limiter, settings, p.username):
            alerts.send("login_lockout")
        audit.record(db, action, actor=p.user_id, target_user=target_user, ip=ip)
        db.commit()
        raise ApiError(403, "invalid_credentials", "password is wrong")


@router.delete(
    "/users/{user_id}",
    status_code=204,
    summary="Remove a user (needs the administrator's own password)",
    responses=error_responses(403, 404, 409, 413, 422, 429),
)
def remove_user(
    user_id: str,
    body: SelfDelete,
    p: Principal = Depends(require_admin_write),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    alerts: AdminAlerts = Depends(get_alerts),
    ip: str = Depends(get_ip),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    confirm_admin_password(p, body.password, "user_remove_failed", user_id, settings, limiter, alerts, ip, rdb, db)
    if user_id == p.user_id:
        raise conflict("administrators cannot remove themselves")
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    accounts.disable_user(db, user)
    audit.record(db, "user_removed", actor=p.user_id, target_user=user.id, ip=ip, was_admin=user.is_admin)
    db.commit()
    return Response(status_code=204)


@router.post(
    "/users/{user_id}/password-reset",
    status_code=201,
    response_model=PasswordResetCreated,
    summary="Issue a password reset code for a user (needs the administrator's own password)",
    responses=error_responses(403, 404, 409, 413, 422, 429),
)
def issue_password_reset(
    user_id: str,
    body: SelfDelete,
    p: Principal = Depends(require_admin_write),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    alerts: AdminAlerts = Depends(get_alerts),
    ip: str = Depends(get_ip),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    confirm_admin_password(p, body.password, "password_reset_denied", user_id, settings, limiter, alerts, ip, rdb, db)
    if user_id == p.user_id:
        raise conflict("administrators cannot issue a reset code for themselves; change the password instead")
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    code, expires_at = password_reset.issue(db, user, p.user_id, ip)
    db.commit()
    return {"code": code, "expires_at": expires_at}


@router.delete(
    "/users/{user_id}/devices/{device_id}",
    status_code=204,
    summary="Revoke a user's device",
    responses=error_responses(404, 409),
)
def revoke_user_device(
    user_id: str,
    device_id: str,
    p: Principal = Depends(require_admin_write),
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


def _admin_users(db: Session, user_id: str | None = None) -> list[dict]:
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
    query = (
        select(User, devices.c.last_seen_at, devices.c.active, files.c.bytes)
        .outerjoin(devices, devices.c.user_id == User.id)
        .outerjoin(files, files.c.user_id == User.id)
        .order_by(User.created_at, User.id)
    )
    if user_id is not None:
        query = query.where(User.id == user_id)
    rows = db.execute(query).all()
    by_user: dict[str, list[dict]] = {}
    if rows:
        dquery = select(Device).where(Device.revoked_at.is_(None)).order_by(Device.user_id, Device.number)
        if user_id is not None:
            dquery = dquery.where(Device.user_id == user_id)
        for d in db.scalars(dquery):
            by_user.setdefault(d.user_id, []).append(
                {
                    "id": d.id,
                    "number": d.number,
                    "name": d.name,
                    "last_seen_at": d.last_seen_at,
                    "push_registered": bool(d.push_kind and d.push_endpoint),
                    "last_push_at": d.last_push_at,
                    "last_push_status": d.last_push_status,
                    "push_failures": d.push_failures,
                }
            )
    return [
        {
            "id": u.id,
            "username": u.username,
            "display_name": u.display_name,
            "is_admin": u.is_admin,
            "created_at": u.created_at,
            "disabled_at": u.disabled_at,
            "suspended_at": u.suspended_at,
            "last_seen_at": last_seen,
            "active_devices": int(active or 0),
            "attachment_bytes": int(size or 0),
            "devices": by_user.get(u.id, []),
        }
        for u, last_seen, active, size in rows
    ]


@router.get("/users", response_model=list[AdminUserOut], summary="List all accounts with usage figures")
def list_users(_: Principal = Depends(require_admin), db: Session = Depends(get_read_db)):
    return _admin_users(db)


def _active_target(db: Session, user_id: str) -> User:
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    return user


@router.post(
    "/users/{user_id}/suspend",
    status_code=204,
    summary="Suspend a user",
    responses=error_responses(404, 409),
)
def suspend_user(
    user_id: str,
    p: Principal = Depends(require_admin_write),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    """Sign-in and API use are refused (`403 account_suspended`) and open sockets are closed; data, memberships and
    devices stay, so unsuspending restores everything. Idempotent."""
    if user_id == p.user_id:
        raise conflict("administrators cannot suspend themselves")
    user = _active_target(db, user_id)
    if user.is_admin and user.suspended_at is None and accounts.active_admin_count(db, excluding=user.id) == 0:
        raise conflict("the last active administrator cannot be suspended", "last_admin")
    if accounts.suspend_user(db, user):
        audit.record(db, "user_suspended", actor=p.user_id, target_user=user.id, ip=ip, is_admin=user.is_admin)
        db.commit()
    return Response(status_code=204)


@router.post(
    "/users/{user_id}/unsuspend",
    status_code=204,
    summary="Unsuspend a user",
    responses=error_responses(404),
)
def unsuspend_user(
    user_id: str,
    p: Principal = Depends(require_admin_write),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    user = _active_target(db, user_id)
    if accounts.unsuspend_user(db, user):
        audit.record(db, "user_unsuspended", actor=p.user_id, target_user=user.id, ip=ip)
        db.commit()
    return Response(status_code=204)


@router.patch(
    "/users/{user_id}",
    response_model=AdminUserOut,
    summary="Promote or demote a user",
    responses=error_responses(404, 409, 413, 422),
)
def update_user(
    user_id: str,
    body: AdminUserUpdate,
    p: Principal = Depends(require_admin_write),
    ip: str = Depends(get_ip),
    alerts: AdminAlerts = Depends(get_alerts),
    db: Session = Depends(get_db),
):
    """Set `is_admin`. The last active administrator (not removed, not suspended) cannot be demoted, yourself
    included (`409 last_admin`). Writers are serialised by the database lock, so two admins demoting each other at
    once cannot both succeed."""
    user = _active_target(db, user_id)
    if bool(user.is_admin) != body.is_admin:
        if not body.is_admin and user.suspended_at is None and accounts.active_admin_count(db, excluding=user.id) == 0:
            raise conflict("the last administrator cannot be demoted", "last_admin")
        user.is_admin = body.is_admin
        db.flush()
        audit.record(
            db,
            "admin_granted" if body.is_admin else "admin_revoked",
            actor=p.user_id,
            target_user=user.id,
            ip=ip,
            via="admin",
        )
        accounts.broadcast_user_updated(db, user)
        db.commit()
        if body.is_admin:
            alerts.send("admin_granted")
    (out,) = _admin_users(db, user.id)
    return out


@router.get("/stats", response_model=AdminStats, summary="Server statistics")
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
        "db_maintenance": dbmaint.last_run(db),
    }


@router.get("/audit", response_model=list[AuditEventOut], summary="Read the audit log", responses=error_responses(422))
def get_audit(
    before_id: int | None = Query(None, ge=1, le=2**62),
    limit: int = Query(50, ge=1, le=200),
    _: Principal = Depends(require_admin),
    db: Session = Depends(get_read_db),
):
    """Newest first. Page backwards with `before_id` = the smallest `id` of the previous page."""
    return [audit.event_out(e) for e in audit.list_events(db, before_id, limit)]

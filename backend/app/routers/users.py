from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import clock
from app.config import Settings
from app.deps import Principal, get_db, get_ip, get_limiter, get_principal, get_read_db, get_settings_dep
from app.errors import ApiError, conflict, error_responses, invalid, not_found
from app.models import Attachment, Device, User
from app.ratelimit import RateLimiter
from app.schemas import (
    DeviceOut,
    PasswordChange,
    ProfileUpdate,
    SelfDelete,
    UserListItemOut,
    UserOut,
    UserSettingsOut,
    UserSettingsUpdate,
)
from app.security import hash_password, verify_password
from app.services import accounts, audit, presence
from app.services.views import devices_out_db, user_out

router = APIRouter(prefix="/users", tags=["users"], responses=error_responses(401))


@router.get("", response_model=list[UserListItemOut], summary="List active users")
def list_users(request: Request, _: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    """`online` and `last_seen_at` are filled in only for users who opted in to sharing presence (null otherwise)."""
    online_ids = request.app.state.hub.connected_user_ids()
    now = clock.now_ms()
    out = []
    for u in db.scalars(select(User).where(User.disabled_at.is_(None)).order_by(User.created_at)):
        item = user_out(u)
        if u.share_presence:
            online = u.id in online_ids
            item["online"] = online
            item["last_seen_at"] = now if online else u.presence_last_seen_at
        out.append(item)
    return out


@router.get("/me", response_model=UserOut, summary="Get the calling user")
def me(p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    return user_out(db.get(User, p.user_id))


@router.patch(
    "/me", response_model=UserOut, summary="Update the calling user's profile", responses=error_responses(413, 422)
)
def update_me(body: ProfileUpdate, p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    user = db.get(User, p.user_id)
    fields = body.model_fields_set
    if "display_name" in fields:
        if body.display_name is None:
            raise invalid("display_name cannot be null")
        user.display_name = body.display_name
    if "avatar_attachment_id" in fields:
        if body.avatar_attachment_id is not None:
            att = db.get(Attachment, body.avatar_attachment_id)
            if att is None or att.purpose != "avatar" or att.uploader_id != p.user_id:
                raise invalid("avatar_attachment_id must be an avatar you uploaded")
        user.avatar_attachment_id = body.avatar_attachment_id
    db.flush()
    if fields:
        accounts.broadcast_user_updated(db, user)
    db.commit()
    return user_out(user)


@router.get("/me/settings", response_model=UserSettingsOut, summary="Get the calling user's settings")
def get_settings(p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    return {"share_presence": bool(db.scalar(select(User.share_presence).where(User.id == p.user_id)))}


@router.patch(
    "/me/settings",
    response_model=UserSettingsOut,
    summary="Update the calling user's settings",
    responses=error_responses(413, 422),
)
def update_settings(
    body: UserSettingsUpdate,
    request: Request,
    p: Principal = Depends(get_principal),
    addr: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    """`share_presence` (default false): let users who share a conversation with you see when you are online."""
    user = db.get(User, p.user_id)
    changed = body.share_presence is not None and body.share_presence != bool(user.share_presence)
    if changed:
        user.share_presence = body.share_presence
        user.presence_last_seen_at = None  # nothing from the sharing period is kept
        audit.record(
            db, "presence_setting_changed", actor=p.user_id, target_user=p.user_id, ip=addr, share_presence=user.share_presence
        )
    db.commit()
    if changed:
        presence.settings_changed(request.app, p.user_id, user.share_presence)
    return {"share_presence": bool(user.share_presence)}


@router.delete(
    "/me",
    status_code=204,
    summary="Delete the calling user's own account",
    responses=error_responses(403, 409, 413, 422, 429),
)
def delete_me(
    body: SelfDelete,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    addr: str = Depends(get_ip),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    """Re-authenticates with the password, then has the same effect as an administrator removing the account:
    devices, sessions and keys are revoked, the user leaves every conversation (their messages stay in them), and
    their avatar and unreferenced attachments become garbage-collectable."""
    limiter.check(f"self-delete:{p.user_id}", 5)
    current = rdb.scalar(select(User.password_hash).where(User.id == p.user_id))
    rdb.rollback()  # no transaction while Argon2 runs
    if current is None or not verify_password(settings, current, body.password):
        audit.record(db, "self_delete_failed", actor=p.user_id, target_user=p.user_id, ip=addr)
        db.commit()
        raise ApiError(403, "invalid_credentials", "password is wrong")
    user = db.get(User, p.user_id)
    if user is None or user.disabled_at is not None:
        raise ApiError(401, "unauthorized", "session is no longer valid")
    if user.is_admin and accounts.active_admin_count(db, excluding=user.id) == 0:
        raise conflict("you are the last administrator; make someone else an administrator first", "last_admin")
    accounts.disable_user(db, user)
    audit.record(db, "user_removed", actor=p.user_id, target_user=user.id, ip=addr, was_admin=user.is_admin, via="self")
    db.commit()
    return Response(status_code=204)


@router.put(
    "/me/password",
    status_code=204,
    summary="Change the calling user's password",
    responses=error_responses(403, 409, 413, 422, 429),
)
def change_password(
    body: PasswordChange,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    addr: str = Depends(get_ip),
    rdb: Session = Depends(get_read_db),
    db: Session = Depends(get_db),
):
    limiter.check(f"password:{p.user_id}", 5)
    current = rdb.scalar(select(User.password_hash).where(User.id == p.user_id))
    rdb.rollback()  # no transaction while Argon2 runs
    if current is None or not verify_password(settings, current, body.current_password):
        raise ApiError(403, "invalid_credentials", "current password is wrong")
    new_hash = hash_password(settings, body.new_password)
    user = db.get(User, p.user_id)
    if user is None or user.password_hash != current:  # changed by a concurrent request while we were hashing
        db.rollback()
        raise conflict("the password was changed concurrently; try again")
    user.password_hash = new_hash
    accounts.revoke_other_sessions(db, p.user_id, p.device_id)
    audit.record(db, "password_changed", actor=p.user_id, target_user=p.user_id, ip=addr)
    db.commit()
    return Response(status_code=204)


@router.get(
    "/{user_id}/devices",
    response_model=list[DeviceOut],
    summary="List a user's active devices",
    responses=error_responses(404),
)
def user_devices(user_id: str, p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    devices = db.scalars(
        select(Device).where(Device.user_id == user_id, Device.revoked_at.is_(None)).order_by(Device.number)
    ).all()
    out = devices_out_db(db, list(devices))
    if user_id != p.user_id and not user.share_presence:
        # A device's last_seen_at is presence too: others only see it for users who opted in (the creation time
        # stands in; the field is not nullable for older clients).
        for d, row in zip(devices, out, strict=True):
            row["last_seen_at"] = d.created_at
    return out

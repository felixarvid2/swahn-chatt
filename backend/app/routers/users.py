from fastapi import APIRouter, Depends, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import Settings
from app.deps import Principal, get_db, get_ip, get_limiter, get_principal, get_settings_dep
from app.errors import ApiError, invalid, not_found
from app.models import Attachment, Device, User
from app.ratelimit import RateLimiter
from app.schemas import DeviceOut, PasswordChange, ProfileUpdate, UserOut
from app.security import hash_password, verify_password
from app.services import accounts
from app.services.views import devices_out_db, user_out

router = APIRouter(prefix="/users", tags=["users"])


@router.get("", response_model=list[UserOut])
def list_users(_: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    return [user_out(u) for u in db.scalars(select(User).where(User.disabled_at.is_(None)).order_by(User.created_at))]


@router.get("/me", response_model=UserOut)
def me(p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    return user_out(db.get(User, p.user_id))


@router.patch("/me", response_model=UserOut)
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


@router.put("/me/password", status_code=204)
def change_password(
    body: PasswordChange,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    ip: str = Depends(get_ip),
    db: Session = Depends(get_db),
):
    limiter.check(f"password:{p.user_id}", 5)
    user = db.get(User, p.user_id)
    if not verify_password(settings, user.password_hash, body.current_password):
        raise ApiError(403, "invalid_credentials", "current password is wrong")
    user.password_hash = hash_password(settings, body.new_password)
    accounts.revoke_other_sessions(db, p.user_id, p.device_id)
    db.commit()
    return Response(status_code=204)


@router.get("/{user_id}/devices", response_model=list[DeviceOut])
def user_devices(user_id: str, _: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    devices = db.scalars(
        select(Device).where(Device.user_id == user_id, Device.revoked_at.is_(None)).order_by(Device.number)
    ).all()
    return devices_out_db(db, list(devices))

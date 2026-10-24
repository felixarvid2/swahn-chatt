from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.config import Settings
from app.deps import Principal, get_db, get_principal, get_settings_dep
from app.errors import invalid, not_found
from app.models import Device
from app.schemas import DeviceOut, PushRegistration
from app.services import accounts
from app.services.push import endpoint_allowed, parse_hosts
from app.services.views import device_out_db

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("/me", response_model=DeviceOut)
def my_device(p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    return device_out_db(db, db.get(Device, p.device_id))


@router.put("/me/push", status_code=204)
def set_push(
    body: PushRegistration,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
):
    if not endpoint_allowed(body.endpoint, settings.push_allow_http, parse_hosts(settings.push_allowed_hosts)):
        raise invalid("endpoint must be an https URL on an allowed push host")
    device = db.get(Device, p.device_id)
    device.push_kind = body.kind
    device.push_endpoint = body.endpoint
    db.commit()
    return Response(status_code=204)


@router.delete("/me/push", status_code=204)
def clear_push(p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    device = db.get(Device, p.device_id)
    device.push_kind = None
    device.push_endpoint = None
    db.commit()
    return Response(status_code=204)


@router.delete("/{device_id}", status_code=204)
def delete_device(device_id: str, p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    device = db.get(Device, device_id)
    if device is None or device.user_id != p.user_id or device.revoked_at is not None:
        raise not_found("device not found")
    accounts.revoke_device(db, device_id)
    db.commit()
    return Response(status_code=204)

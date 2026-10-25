from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.orm import Session

from app.config import Settings
from app.deps import Principal, get_db, get_ip, get_limiter, get_principal, get_read_db, get_settings_dep
from app.errors import conflict, error_responses, invalid, not_found
from app.models import Device
from app.ratelimit import RateLimiter
from app.schemas import DeviceOut, DeviceRename, OwnDeviceOut, PushRegistration, PushTestQueued
from app.services import accounts, audit
from app.services.events import active_device_ids, co_member_user_ids, emit
from app.services.push import endpoint_allowed, parse_hosts
from app.services.views import device_out_db

router = APIRouter(prefix="/devices", tags=["devices"], responses=error_responses(401))


@router.get("/me", response_model=OwnDeviceOut, summary="Get the calling device")
def my_device(p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    device = db.get(Device, p.device_id)
    return {
        **device_out_db(db, device),
        "last_push_at": device.last_push_at,
        "last_push_status": device.last_push_status,
    }


@router.put(
    "/me/push",
    status_code=204,
    summary="Register the UnifiedPush endpoint of this device",
    responses=error_responses(409, 413, 422),
)
def set_push(
    body: PushRegistration,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    db: Session = Depends(get_db),
):
    hosts = parse_hosts(settings.push_allowed_hosts)
    if settings.push_requires_allowlist and not hosts:
        raise conflict(
            "push is not configured on this server: the administrator must set SWAHNCHAT_PUSH_ALLOWED_HOSTS",
            "push_not_configured",
        )
    if not endpoint_allowed(body.endpoint, settings.push_allow_http, hosts, settings.push_requires_allowlist):
        raise invalid("endpoint must be an https URL on an allowed push host")
    device = db.get(Device, p.device_id)
    device.push_kind = body.kind
    device.push_endpoint = body.endpoint
    device.push_failures = 0
    db.commit()
    return Response(status_code=204)


PUSH_TESTS_PER_WINDOW = 3
PUSH_TEST_WINDOW_S = 600.0


@router.post(
    "/me/push/test",
    status_code=202,
    response_model=PushTestQueued,
    summary="Send a test push to this device",
    responses=error_responses(409, 429),
)
def test_push(
    request: Request,
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
    db: Session = Depends(get_read_db),
):
    """Queues one content-free push (the same `{"t":"sync"}` as every push) to the endpoint this device registered.
    The outcome shows up in `last_push_at` / `last_push_status` of `GET /devices/me`."""
    device = db.get(Device, p.device_id)
    hosts = parse_hosts(settings.push_allowed_hosts)
    if (
        (settings.push_requires_allowlist and not hosts)
        or device.push_kind != "unifiedpush"
        or not device.push_endpoint
        or not endpoint_allowed(device.push_endpoint, settings.push_allow_http, hosts, settings.push_requires_allowlist)
    ):
        raise conflict("no usable push endpoint is registered for this device", "push_not_configured")
    limiter.check(f"push-test:{p.device_id}", PUSH_TESTS_PER_WINDOW, PUSH_TEST_WINDOW_S)
    request.app.state.push.notify([p.device_id])
    return {"status": "queued"}


@router.delete("/me/push", status_code=204, summary="Remove the push endpoint of this device")
def clear_push(p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    device = db.get(Device, p.device_id)
    device.push_kind = None
    device.push_endpoint = None
    db.commit()
    return Response(status_code=204)


DEVICE_RENAMES_PER_MINUTE = 20


@router.patch(
    "/{device_id}",
    response_model=DeviceOut,
    summary="Rename one of the caller's own devices",
    responses=error_responses(404, 413, 422, 429),
)
def rename_device(
    device_id: str,
    body: DeviceRename,
    p: Principal = Depends(get_principal),
    addr: str = Depends(get_ip),
    limiter: RateLimiter = Depends(get_limiter),
    db: Session = Depends(get_db),
):
    limiter.check(f"device-rename:{p.user_id}", DEVICE_RENAMES_PER_MINUTE)
    device = db.get(Device, device_id)
    if device is None or device.user_id != p.user_id or device.revoked_at is not None:
        raise not_found("device not found")
    if device.name != body.name:
        device.name = body.name
        # The name is free text, so the audit entry carries only the device id, like the other device entries.
        audit.record(db, "device_renamed", actor=p.user_id, target_user=p.user_id, target=device_id, ip=addr)
        targets = [d for d in active_device_ids(db, co_member_user_ids(db, p.user_id)) if d != p.device_id]
        emit(db, targets, "devices_changed", {"user_id": p.user_id})
        db.commit()
    return device_out_db(db, device)


@router.delete(
    "/{device_id}", status_code=204, summary="Revoke one of the caller's own devices", responses=error_responses(404)
)
def delete_device(
    device_id: str, p: Principal = Depends(get_principal), addr: str = Depends(get_ip), db: Session = Depends(get_db)
):
    device = db.get(Device, device_id)
    if device is None or device.user_id != p.user_id or device.revoked_at is not None:
        raise not_found("device not found")
    accounts.revoke_device(db, device_id)
    audit.record(db, "device_revoked", actor=p.user_id, target_user=p.user_id, target=device_id, ip=addr, by="self")
    db.commit()
    return Response(status_code=204)

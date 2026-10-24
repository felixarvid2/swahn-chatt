from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_limiter, get_principal, get_read_db
from app.errors import invalid, not_found
from app.models import User
from app.ratelimit import RateLimiter
from app.schemas import UUID_RE, KeyStatus, KeyUpload
from app.services import keys

router = APIRouter(prefix="/keys", tags=["keys"])

KEY_FETCHES_PER_MINUTE = 120
KEY_BUNDLES_PER_TARGET_HOUR = 10


@router.put("", status_code=204)
def put_keys(body: KeyUpload, p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    keys.upload(db, p.device_id, body)
    db.commit()
    return Response(status_code=204)


@router.get("/status", response_model=KeyStatus)
def key_status(p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    return keys.status(db, p.device_id)


@router.get("/{user_id}")
def get_bundles(
    user_id: str,
    device_id: str | None = None,
    p: Principal = Depends(get_principal),
    limiter: RateLimiter = Depends(get_limiter),
    db: Session = Depends(get_db),
):
    # One-time prekeys are what makes a first message forward secret, so they must not be drainable: the limits
    # are per caller *user* (logging in again makes a new device, which must not reset them), and per target
    # device only KEY_BUNDLES_PER_TARGET_HOUR fetches an hour still receive one-time keys.
    limiter.check(f"keys:{p.user_id}", KEY_FETCHES_PER_MINUTE)
    if device_id is not None and not UUID_RE.match(device_id):
        raise invalid("device_id must be a UUID")
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")

    def may_consume(target_device_id: str) -> bool:
        return limiter.allow(f"keys-target:{p.user_id}:{target_device_id}", KEY_BUNDLES_PER_TARGET_HOUR, 3600.0)

    devices = keys.fetch_bundles(db, user_id, p.device_id, device_id, may_consume)
    db.commit()
    return {"user_id": user_id, "devices": devices}

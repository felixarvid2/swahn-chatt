from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_limiter, get_principal
from app.errors import invalid, not_found
from app.models import User
from app.ratelimit import RateLimiter
from app.schemas import UUID_RE, KeyStatus, KeyUpload
from app.services import keys

router = APIRouter(prefix="/keys", tags=["keys"])


@router.put("", status_code=204)
def put_keys(body: KeyUpload, p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    keys.upload(db, p.device_id, body)
    db.commit()
    return Response(status_code=204)


@router.get("/status", response_model=KeyStatus)
def key_status(p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    return keys.status(db, p.device_id)


@router.get("/{user_id}")
def get_bundles(
    user_id: str,
    device_id: str | None = None,
    p: Principal = Depends(get_principal),
    limiter: RateLimiter = Depends(get_limiter),
    db: Session = Depends(get_db),
):
    limiter.check(f"keys:{p.device_id}", 120)
    if device_id is not None and not UUID_RE.match(device_id):
        raise invalid("device_id must be a UUID")
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    devices = keys.fetch_bundles(db, user_id, p.device_id, device_id)
    db.commit()
    return {"user_id": user_id, "devices": devices}

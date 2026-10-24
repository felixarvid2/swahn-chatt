"""Public key storage and one-time prekey consumption (the server never sees private keys)."""

from collections.abc import Callable

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app import clock
from app.errors import conflict, invalid
from app.models import Device, DeviceKey, OneTimePqPrekey, OneTimePrekey, User
from app.schemas import KeyUpload
from app.services.events import active_device_ids, co_member_user_ids, emit

MAX_STORED_ONE_TIME = 1000


def upload(db: Session, device_id: str, req: KeyUpload) -> None:
    row = db.get(DeviceKey, device_id)
    now = clock.now_ms()
    changed = False
    if row is None:
        if not (
            req.registration_id is not None and req.identity_key and req.signed_prekey and req.pq_last_resort_prekey
        ):
            raise invalid("first upload needs registration_id, identity_key, signed_prekey and pq_last_resort_prekey")
        row = DeviceKey(
            device_id=device_id,
            registration_id=req.registration_id,
            identity_key=req.identity_key,
            signed_prekey_id=req.signed_prekey.key_id,
            signed_prekey_public=req.signed_prekey.public_key,
            signed_prekey_signature=req.signed_prekey.signature,
            pq_last_resort_id=req.pq_last_resort_prekey.key_id,
            pq_last_resort_public=req.pq_last_resort_prekey.public_key,
            pq_last_resort_signature=req.pq_last_resort_prekey.signature,
            updated_at=now,
        )
        db.add(row)
        changed = True
    else:
        if req.identity_key is not None and req.identity_key != row.identity_key:
            raise conflict("a device's identity key can never change; register a new device", "identity_key_mismatch")
        if req.registration_id is not None and req.registration_id != row.registration_id:
            row.registration_id = req.registration_id
            changed = True
        if req.signed_prekey is not None:
            row.signed_prekey_id = req.signed_prekey.key_id
            row.signed_prekey_public = req.signed_prekey.public_key
            row.signed_prekey_signature = req.signed_prekey.signature
            changed = True
        if req.pq_last_resort_prekey is not None:
            row.pq_last_resort_id = req.pq_last_resort_prekey.key_id
            row.pq_last_resort_public = req.pq_last_resort_prekey.public_key
            row.pq_last_resort_signature = req.pq_last_resort_prekey.signature
            changed = True
        row.updated_at = now
    db.flush()

    have = set(db.scalars(select(OneTimePrekey.key_id).where(OneTimePrekey.device_id == device_id)))
    new_ec = [k for k in {k.key_id: k for k in req.one_time_prekeys}.values() if k.key_id not in have]
    have_pq = set(db.scalars(select(OneTimePqPrekey.key_id).where(OneTimePqPrekey.device_id == device_id)))
    new_pq = [k for k in {k.key_id: k for k in req.one_time_pq_prekeys}.values() if k.key_id not in have_pq]
    if len(have) + len(new_ec) > MAX_STORED_ONE_TIME or len(have_pq) + len(new_pq) > MAX_STORED_ONE_TIME:
        raise invalid(f"at most {MAX_STORED_ONE_TIME} one-time keys of each kind may be stored")
    db.add_all(OneTimePrekey(device_id=device_id, key_id=k.key_id, public_key=k.public_key) for k in new_ec)
    db.add_all(
        OneTimePqPrekey(device_id=device_id, key_id=k.key_id, public_key=k.public_key, signature=k.signature)
        for k in new_pq
    )
    db.flush()
    if changed:
        user_id = db.scalar(select(Device.user_id).where(Device.id == device_id))
        targets = [d for d in active_device_ids(db, co_member_user_ids(db, user_id)) if d != device_id]
        emit(db, targets, "devices_changed", {"user_id": user_id})


def status(db: Session, device_id: str) -> dict:
    row = db.get(DeviceKey, device_id)
    return {
        "one_time_prekeys": db.scalar(
            select(func.count()).select_from(OneTimePrekey).where(OneTimePrekey.device_id == device_id)
        ),
        "one_time_pq_prekeys": db.scalar(
            select(func.count()).select_from(OneTimePqPrekey).where(OneTimePqPrekey.device_id == device_id)
        ),
        "signed_prekey_id": row.signed_prekey_id if row else None,
        "pq_last_resort_prekey_id": row.pq_last_resort_id if row else None,
    }


def _first_one_time(db: Session, model, device_id: str):
    return db.scalars(
        select(model)
        .where(model.device_id == device_id)
        .order_by(model.key_id)
        .limit(1)
        .with_for_update(skip_locked=True)
    ).first()


def fetch_bundles(
    db: Session,
    user_id: str,
    exclude_device_id: str,
    only_device_id: str | None,
    may_consume: Callable[[str], bool] | None = None,
) -> list[dict]:
    """Bundles for the user's active devices. `may_consume(device_id)` False means the caller used up its share
    for that device: the bundle then has no one-time keys (signed prekey and last-resort PQ key only) and nothing
    is deleted."""
    q = (
        select(Device, DeviceKey)
        .join(DeviceKey, DeviceKey.device_id == Device.id)
        .join(User, User.id == Device.user_id)
        .where(Device.user_id == user_id, Device.revoked_at.is_(None), User.disabled_at.is_(None))
        .where(Device.id != exclude_device_id)
        .order_by(Device.number)
    )
    if only_device_id:
        q = q.where(Device.id == only_device_id)
    out = []
    for device, key in db.execute(q).all():
        consume = may_consume is None or may_consume(device.id)
        otk = pq = None
        if consume:
            otk = _first_one_time(db, OneTimePrekey, device.id)
            pq = _first_one_time(db, OneTimePqPrekey, device.id)
        bundle = {
            "device_id": device.id,
            "device_number": device.number,
            "registration_id": key.registration_id,
            "identity_key": key.identity_key,
            "signed_prekey": {
                "key_id": key.signed_prekey_id,
                "public_key": key.signed_prekey_public,
                "signature": key.signed_prekey_signature,
            },
            "pq_prekey": (
                {"key_id": pq.key_id, "public_key": pq.public_key, "signature": pq.signature}
                if pq
                else {
                    "key_id": key.pq_last_resort_id,
                    "public_key": key.pq_last_resort_public,
                    "signature": key.pq_last_resort_signature,
                }
            ),
            "one_time_prekey": {"key_id": otk.key_id, "public_key": otk.public_key} if otk else None,
        }
        if otk:
            db.execute(delete(OneTimePrekey).where(OneTimePrekey.id == otk.id))
        if pq:
            db.execute(delete(OneTimePqPrekey).where(OneTimePqPrekey.id == pq.id))
        out.append(bundle)
    return out

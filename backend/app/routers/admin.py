import uuid

from fastapi import APIRouter, Depends, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import clock
from app.deps import Principal, get_db, get_read_db, require_admin
from app.errors import conflict, not_found
from app.models import Invitation, User
from app.schemas import InvitationCreate, InvitationCreated, InvitationOut
from app.security import hash_invite_code, new_invite_code
from app.services import accounts

router = APIRouter(prefix="/admin", tags=["admin"])


def create_invitation(
    db: Session, created_by: str | None, note: str | None, hours: int, is_admin: bool
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
    return inv, code


@router.post("/invitations", status_code=201, response_model=InvitationCreated)
def post_invitation(body: InvitationCreate, p: Principal = Depends(require_admin), db: Session = Depends(get_db)):
    inv, code = create_invitation(db, p.user_id, body.note, body.expires_in_hours, body.is_admin)
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
def revoke_invitation(invitation_id: str, _: Principal = Depends(require_admin), db: Session = Depends(get_db)):
    inv = db.get(Invitation, invitation_id)
    if inv is None:
        raise not_found("invitation not found")
    if inv.revoked_at is None:
        inv.revoked_at = clock.now_ms()
        db.commit()
    return Response(status_code=204)


@router.delete("/users/{user_id}", status_code=204)
def remove_user(user_id: str, p: Principal = Depends(require_admin), db: Session = Depends(get_db)):
    if user_id == p.user_id:
        raise conflict("administrators cannot remove themselves")
    user = db.get(User, user_id)
    if user is None or user.disabled_at is not None:
        raise not_found("user not found")
    accounts.disable_user(db, user)
    db.commit()
    return Response(status_code=204)

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_principal, get_read_db
from app.errors import error_responses, invalid
from app.models import ConversationMember
from app.schemas import (
    AddMembers,
    ConversationCreate,
    ConversationOut,
    ConversationUpdate,
    MessageOut,
    ReadRequest,
    RoleChange,
)
from app.services import conversations as svc
from app.services import messages as msgs
from app.services.views import conversations_out

router = APIRouter(prefix="/conversations", tags=["conversations"], responses=error_responses(401))


@router.get("", response_model=list[ConversationOut], summary="List the caller's conversations")
def list_conversations(p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    ids = db.scalars(select(ConversationMember.conversation_id).where(ConversationMember.user_id == p.user_id)).all()
    return conversations_out(db, ids, p.user_id)


@router.post(
    "",
    response_model=ConversationOut,
    summary="Create a direct or group conversation",
    responses=error_responses(404, 409, 413, 422),
)
def create_conversation(
    body: ConversationCreate, response: Response, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    if body.type == "direct":
        if len(body.member_user_ids) != 1:
            raise invalid("a direct conversation needs exactly one other member_user_id")
        conv_id, created = svc.create_direct(db, p.user_id, body.member_user_ids[0])
        response.status_code = 201 if created else 200
    else:
        if not body.name:
            raise invalid("a group needs a name")
        conv_id = svc.create_group(db, p.user_id, body.name, body.member_user_ids, body.avatar_attachment_id)
        response.status_code = 201
    db.commit()
    return svc.conversation_for(db, conv_id, p.user_id)


@router.get(
    "/{conversation_id}", response_model=ConversationOut, summary="Get a conversation", responses=error_responses(404)
)
def get_conversation(conversation_id: str, p: Principal = Depends(get_principal), db: Session = Depends(get_read_db)):
    svc.require_member(db, conversation_id, p.user_id)
    return svc.conversation_for(db, conversation_id, p.user_id)


@router.patch(
    "/{conversation_id}",
    response_model=ConversationOut,
    summary="Update a conversation (name, avatar, disappearing timer)",
    responses=error_responses(403, 404, 409, 413, 422),
)
def update_conversation(
    conversation_id: str, body: ConversationUpdate, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    svc.update_conversation(db, conversation_id, p.user_id, body)
    db.commit()
    return svc.conversation_for(db, conversation_id, p.user_id)


@router.post(
    "/{conversation_id}/members",
    response_model=ConversationOut,
    summary="Add members to a group",
    responses=error_responses(403, 404, 409, 413, 422),
)
def add_members(
    conversation_id: str, body: AddMembers, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    conv = svc.require_group_admin(db, conversation_id, p.user_id)
    svc.add_members(db, conv, body.user_ids)
    db.commit()
    return svc.conversation_for(db, conversation_id, p.user_id)


@router.patch(
    "/{conversation_id}/members/{user_id}",
    response_model=ConversationOut,
    summary="Change a member's role",
    responses=error_responses(403, 404, 409, 413, 422),
)
def change_role(
    conversation_id: str,
    user_id: str,
    body: RoleChange,
    p: Principal = Depends(get_principal),
    db: Session = Depends(get_db),
):
    conv = svc.require_group_admin(db, conversation_id, p.user_id)
    svc.set_role(db, conv, user_id, body.role)
    db.commit()
    return svc.conversation_for(db, conversation_id, p.user_id)


@router.delete(
    "/{conversation_id}/members/{user_id}",
    status_code=204,
    summary="Remove a member or leave",
    responses=error_responses(403, 404, 409),
)
def remove_member(
    conversation_id: str, user_id: str, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    conv, me = svc.require_member(db, conversation_id, p.user_id)
    svc.remove_member_checked(db, conv, p.user_id, me.role, user_id)
    db.commit()
    return Response(status_code=204)


@router.get(
    "/{conversation_id}/messages",
    response_model=list[MessageOut],
    summary="Message history of a conversation",
    responses=error_responses(404, 422),
)
def list_messages(
    conversation_id: str,
    before_seq: int | None = Query(None, ge=0, le=2**62),
    limit: int = Query(50, ge=1, le=200),
    p: Principal = Depends(get_principal),
    db: Session = Depends(get_read_db),
):
    conv, _ = svc.require_member(db, conversation_id, p.user_id)
    return msgs.list_messages(db, conv, p.user_id, p.device_id, before_seq, limit)


@router.post(
    "/{conversation_id}/read",
    status_code=204,
    summary="Move the read pointer forward",
    responses=error_responses(404, 413, 422),
)
def mark_read(
    conversation_id: str, body: ReadRequest, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    conv, me = svc.require_member(db, conversation_id, p.user_id)
    svc.mark_read(db, conv, me, p.device_id, body.up_to_seq, body.notify)
    db.commit()
    return Response(status_code=204)

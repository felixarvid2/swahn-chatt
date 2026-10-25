from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_limiter, get_principal
from app.errors import error_responses
from app.ratelimit import RateLimiter
from app.schemas import SendMessage, SendResult
from app.services import messages as svc

CALL_MESSAGES_PER_MINUTE = 60

router = APIRouter(prefix="/messages", tags=["messages"], responses=error_responses(401))


@router.post(
    "",
    response_model=SendResult,
    status_code=201,
    summary="Send a message, edit, delete, reaction or call signal",
    responses=error_responses(403, 404, 409, 413, 422),
)
def send_message(
    body: SendMessage,
    response: Response,
    p: Principal = Depends(get_principal),
    db: Session = Depends(get_db),
    limiter: RateLimiter = Depends(get_limiter),
):
    if body.kind == "call":
        limiter.check(f"call-msg:{p.user_id}", CALL_MESSAGES_PER_MINUTE)
    message, duplicate = svc.send(db, p, body)
    db.commit()
    response.status_code = 200 if duplicate else 201
    return {"message": message, "duplicate": duplicate}

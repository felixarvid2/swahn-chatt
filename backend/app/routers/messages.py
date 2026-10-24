from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_principal
from app.errors import error_responses
from app.schemas import SendMessage, SendResult
from app.services import messages as svc

router = APIRouter(prefix="/messages", tags=["messages"], responses=error_responses(401))


@router.post(
    "",
    response_model=SendResult,
    status_code=201,
    summary="Send a message, edit, delete or reaction",
    responses=error_responses(403, 404, 409, 413, 422),
)
def send_message(
    body: SendMessage, response: Response, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    message, duplicate = svc.send(db, p, body)
    db.commit()
    response.status_code = 200 if duplicate else 201
    return {"message": message, "duplicate": duplicate}

from fastapi import APIRouter, Depends, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_principal
from app.schemas import SendMessage, SendResult
from app.services import messages as svc

router = APIRouter(prefix="/messages", tags=["messages"])


@router.post("", response_model=SendResult, status_code=201)
def send_message(
    body: SendMessage, response: Response, p: Principal = Depends(get_principal), db: Session = Depends(get_db)
):
    message, duplicate = svc.send(db, p, body)
    db.commit()
    response.status_code = 200 if duplicate else 201
    return {"message": message, "duplicate": duplicate}

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_principal, get_read_db
from app.schemas import AckRequest, SyncResponse
from app.services import sync as svc

router = APIRouter(prefix="/sync", tags=["sync"])


@router.get("", response_model=SyncResponse)
def get_sync(
    after: int = Query(0, ge=0),
    limit: int = Query(200, ge=1, le=500),
    p: Principal = Depends(get_principal),
    db: Session = Depends(get_read_db),
):
    return svc.read_events(db, p, after, limit)


@router.post("/ack", status_code=204)
def ack(body: AckRequest, p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    svc.ack(db, p, body.cursor)
    db.commit()
    return Response(status_code=204)

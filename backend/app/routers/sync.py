from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy.orm import Session

from app.deps import Principal, get_db, get_principal, get_read_db
from app.errors import error_responses
from app.schemas import AckRequest, SyncResponse
from app.services import sync as svc

router = APIRouter(prefix="/sync", tags=["sync"], responses=error_responses(401))


@router.get("", response_model=SyncResponse, summary="Read this device's event queue", responses=error_responses(422))
def get_sync(
    after: int = Query(0, ge=0, le=2**62),
    limit: int = Query(200, ge=1, le=500),
    p: Principal = Depends(get_principal),
    db: Session = Depends(get_read_db),
):
    return svc.read_events(db, p, after, limit)


@router.post("/ack", status_code=204, summary="Acknowledge events up to a cursor", responses=error_responses(413, 422))
def ack(body: AckRequest, p: Principal = Depends(get_principal), db: Session = Depends(get_db)):
    svc.ack(db, p, body.cursor)
    db.commit()
    return Response(status_code=204)

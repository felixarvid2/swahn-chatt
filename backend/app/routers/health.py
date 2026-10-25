import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.migrate import code_head, db_revision
from app.schemas import HealthOut, ReadyOut, UnavailableOut
from app.services.stats import free_disk_bytes

router = APIRouter(tags=["health"])
log = logging.getLogger("swahnchat.health")


@router.get("/healthz", response_model=HealthOut, summary="Liveness probe")
def healthz() -> dict:
    return {"status": "ok"}


def _unavailable(code: str, **extra) -> JSONResponse:
    return JSONResponse({"status": "unavailable", "code": code, **extra}, 503)


@router.get(
    "/readyz",
    response_model=ReadyOut,
    summary="Readiness probe",
    responses={
        503: {
            "model": UnavailableOut,
            "description": "`database_unavailable`, `migration_mismatch` or `low_disk` (see `code`).",
        }
    },
)
def readyz(request: Request):
    """Ready = database answers, it is at the migration this code expects, and the data volume has room."""
    settings = request.app.state.settings
    try:
        with request.app.state.session_factory() as db:
            db.execute(text("SELECT 1"))
        current = db_revision(request.app.state.engine)
    except Exception:
        return _unavailable("database_unavailable")
    head = code_head()
    if current != head:
        return _unavailable("migration_mismatch", migration=current, expected=head)
    if settings.min_free_disk_mb > 0:
        try:
            free = free_disk_bytes(settings.data_dir)
        except OSError:
            log.warning("cannot read free disk space of the data directory", exc_info=True)
            request.app.state.alerts.send("low_disk")
            return _unavailable("low_disk", free_disk_mb=None, min_free_disk_mb=settings.min_free_disk_mb)
        if free < settings.min_free_disk_mb * 1024 * 1024:
            request.app.state.alerts.send("low_disk")  # debounced inside (once per 6 h)
            return _unavailable(
                "low_disk", free_disk_mb=free // (1024 * 1024), min_free_disk_mb=settings.min_free_disk_mb
            )
    return {"status": "ready", "migration": current}

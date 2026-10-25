from fastapi import APIRouter, Depends

from app.config import Settings
from app.deps import Principal, get_limiter, get_principal, get_settings_dep
from app.errors import error_responses
from app.ratelimit import RateLimiter
from app.schemas import TurnCredentials
from app.services import calls as svc

TURN_REQUESTS_PER_MINUTE = 10

router = APIRouter(prefix="/calls", tags=["calls"], responses=error_responses(401))


@router.get(
    "/turn",
    response_model=TurnCredentials,
    summary="Short-lived TURN/STUN credentials for a call",
    responses=error_responses(429),
)
def turn(
    p: Principal = Depends(get_principal),
    settings: Settings = Depends(get_settings_dep),
    limiter: RateLimiter = Depends(get_limiter),
):
    limiter.check(f"turn:{p.user_id}", TURN_REQUESTS_PER_MINUTE)
    return svc.turn_credentials(settings, p.user_id)

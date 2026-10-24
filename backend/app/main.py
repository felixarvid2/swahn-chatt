"""App factory. One worker only: the WebSocket hub, rate limiter and push queue live in this process."""

import asyncio
import contextlib
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.config import Settings, get_settings
from app.db import make_engine, make_session_factory
from app.errors import ApiError, install_error_handlers
from app.hub import Hub
from app.logging_setup import configure_logging
from app.ratelimit import RateLimiter, client_ip
from app.routers import (
    admin,
    attachments,
    auth,
    conversations,
    devices,
    health,
    keys,
    messages,
    sync,
    users,
    ws,
)
from app.services.events import Dispatcher
from app.services.maintenance import prune
from app.services.push import PushService, parse_hosts

log = logging.getLogger("familychat")

JSON_BODY_LIMIT = 8 * 1024 * 1024
MAINTENANCE_INTERVAL_S = 3600


class BodyLimitMiddleware:
    """Reject oversized non-attachment requests (checks Content-Length and counts streamed bytes)."""

    def __init__(self, app: ASGIApp, limit: int = JSON_BODY_LIMIT):
        self.app = app
        self.limit = limit

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope["path"].rstrip("/").endswith("/attachments"):
            await self.app(scope, receive, send)
            return
        headers = dict(scope["headers"])
        length = headers.get(b"content-length")
        if length and length.isdigit() and int(length) > self.limit:
            body = {"code": "payload_too_large", "message": "request body too large"}
            await JSONResponse(body, 413)(scope, receive, send)
            return
        seen = 0

        async def limited() -> Message:
            nonlocal seen
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > self.limit:
                    raise ApiError(413, "payload_too_large", "request body too large")
            return message

        await self.app(scope, limited, send)


def create_app(settings: Settings | None = None, push_transport=None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.attachments_dir.mkdir(parents=True, exist_ok=True)

    engine = make_engine(settings.database_url)
    hub = Hub()
    limiter = RateLimiter(enabled=settings.rate_limit_enabled)
    info: dict = {}
    session_factory = make_session_factory(engine, info)
    push = PushService(
        session_factory,
        allow_http=settings.push_allow_http,
        allowed_hosts=parse_hosts(settings.push_allowed_hosts),
        transport=push_transport,
    )
    info["dispatcher"] = Dispatcher(hub, push)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        push.start()
        task = asyncio.create_task(_maintenance_loop(app)) if settings.background_tasks else None
        log.info("familychat started env=%s", settings.env)
        try:
            yield
        finally:
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
            push.stop()
            engine.dispose()

    docs = settings.docs_enabled
    app = FastAPI(
        title="FamilyChat",
        version="1.0.0",
        lifespan=lifespan,
        docs_url="/api/v1/docs" if docs else None,
        openapi_url="/api/v1/openapi.json" if docs else None,
        redoc_url=None,
    )
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = session_factory
    app.state.hub = hub
    app.state.limiter = limiter
    app.state.push = push

    install_error_handlers(app)
    app.add_middleware(BodyLimitMiddleware)

    @app.middleware("http")
    async def request_log(request: Request, call_next):
        start = time.monotonic()
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        if request.url.path.startswith("/api/") and "cache-control" not in response.headers:
            response.headers["Cache-Control"] = "no-store"
        if request.url.path not in ("/healthz", "/readyz"):
            log.info(
                "request",
                extra={
                    "fields": {
                        "method": request.method,
                        "path": request.url.path,  # never the query string (WebSocket tokens)
                        "status": response.status_code,
                        "ms": round((time.monotonic() - start) * 1000, 1),
                        "ip": client_ip(request, settings.trusted_networks),
                    }
                },
            )
        return response

    app.include_router(health.router)
    api = "/api/v1"
    app.include_router(health.router, prefix=api)
    for module in (auth, admin, users, devices, keys, conversations, messages, sync, attachments, ws):
        app.include_router(module.router, prefix=api)
    return app


async def _maintenance_loop(app: FastAPI) -> None:
    def run() -> None:
        with app.state.session_factory() as db:
            prune(db, app.state.settings)
        app.state.limiter.cleanup()

    while True:
        try:
            await asyncio.to_thread(run)
        except Exception:
            log.exception("maintenance failed")
        await asyncio.sleep(MAINTENANCE_INTERVAL_S)

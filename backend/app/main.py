"""App factory. One worker only: the WebSocket hub, rate limiter and push queue live in this process."""

import asyncio
import contextlib
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app import __version__, metrics
from app.config import Settings, get_settings
from app.db import make_engine, make_read_session_factory, make_session_factory
from app.errors import ApiError, error_body, install_error_handlers
from app.hub import Hub
from app.legacy import adopt_legacy_database
from app.logging_setup import configure_logging
from app.openapi import install_openapi
from app.ratelimit import RateLimiter, client_ip
from app.request_id import RequestIdMiddleware
from app.routers import (
    admin,
    attachments,
    auth,
    calls,
    conversations,
    devices,
    health,
    keys,
    messages,
    sync,
    users,
    ws,
)
from app.services import dbmaint, lockout
from app.services.alerts import AdminAlerts
from app.services.events import Dispatcher
from app.services.maintenance import prune
from app.services.presence import Presence
from app.services.push import PushService, parse_hosts
from app.services.stats import free_disk_bytes

log = logging.getLogger("swahnchat")

JSON_BODY_LIMIT = 8 * 1024 * 1024
MAINTENANCE_INTERVAL_S = 3600
MAINTENANCE_STOP_TIMEOUT_S = 30.0


UPLOAD_PATH = "/api/v1/attachments"


def is_upload(scope: Scope) -> bool:
    """Attachment uploads (POST, and PATCH chunks of a resumable upload) stream to disk and enforce their own limits."""
    path = scope["path"]
    if path.endswith("/"):
        path = path[:-1]
    if scope["method"] == "PATCH" and path.startswith(UPLOAD_PATH + "/uploads/"):
        return True  # resumable upload chunks: the route enforces its own (8 MiB) limit
    return scope["method"] == "POST" and path == UPLOAD_PATH


class BodyLimitMiddleware:
    """Reject oversized non-upload requests (checks Content-Length and counts streamed bytes)."""

    def __init__(self, app: ASGIApp, limit: int = JSON_BODY_LIMIT):
        self.app = app
        self.limit = limit

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or is_upload(scope):
            await self.app(scope, receive, send)
            return
        headers = dict(scope["headers"])
        length = headers.get(b"content-length")
        if length and length.isdigit() and int(length) > self.limit:
            body = error_body("payload_too_large", "request body too large")
            await JSONResponse(body, 413)(scope, receive, send)
            return
        seen = 0
        exceeded = False
        swallow = False

        async def limited() -> Message:
            nonlocal seen, exceeded
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > self.limit:
                    exceeded = True
                    raise ApiError(413, "payload_too_large", "request body too large")
            return message

        async def guarded_send(message: Message) -> None:
            # FastAPI turns any error while reading the body into a 400; report the real reason instead.
            nonlocal swallow
            if exceeded and not swallow and message["type"] == "http.response.start":
                swallow = True
                body = error_body("payload_too_large", "request body too large")
                await JSONResponse(body, 413)(scope, receive, send)
                return
            if not swallow:
                await send(message)

        await self.app(scope, limited, guarded_send)


DOCS_PATH = "/api/v1/docs"
API_CSP = "default-src 'none'; frame-ancestors 'none'"
# Swagger UI (FastAPI's page) loads its script and stylesheet from cdn.jsdelivr.net, the favicon from
# fastapi.tiangolo.com, and starts with one inline script and inline styles. Only the docs page gets this policy.
DOCS_CSP = (
    "default-src 'none'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
    "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; img-src 'self' data: https://fastapi.tiangolo.com "
    "https://cdn.jsdelivr.net; font-src https://cdn.jsdelivr.net; connect-src 'self'; frame-ancestors 'none'"
)
HSTS_VALUE = "max-age=31536000"


class SecurityHeadersMiddleware:
    """Headers every HTTP response carries (errors and the 413 of the body limit included). A header a route set
    itself (the attachment download's Cache-Control, say) is kept."""

    def __init__(self, app: ASGIApp, hsts: bool = False):
        self.app = app
        self.hsts = hsts

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        docs = scope["path"].rstrip("/") == DOCS_PATH

        async def with_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(raw=message["headers"]) if "headers" in message else MutableHeaders()
                message["headers"] = headers.raw
                defaults = {
                    "X-Content-Type-Options": "nosniff",
                    "Referrer-Policy": "no-referrer",
                    "X-Frame-Options": "DENY",
                    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
                    "Cross-Origin-Resource-Policy": "same-site",
                    "Content-Security-Policy": DOCS_CSP if docs else API_CSP,
                    "Cache-Control": "no-store",
                }
                if self.hsts:
                    defaults["Strict-Transport-Security"] = HSTS_VALUE
                for name, value in defaults.items():
                    if name not in headers:
                        headers[name] = value
            await send(message)

        await self.app(scope, receive, with_headers)


def create_app(settings: Settings | None = None, push_transport=None, alert_transport=None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.attachments_dir.mkdir(parents=True, exist_ok=True)

    adopt_legacy_database(settings)
    engine = make_engine(settings.database_url)
    hub = Hub()
    limiter = RateLimiter(enabled=settings.rate_limit_enabled)
    info: dict = {}
    session_factory = make_session_factory(engine, info)
    read_session_factory = make_read_session_factory(engine)
    push = PushService(
        session_factory,
        read_session_factory=read_session_factory,
        allow_http=settings.push_allow_http,
        allowed_hosts=parse_hosts(settings.push_allowed_hosts),
        require_allowlist=settings.push_requires_allowlist,
        transport=push_transport,
    )
    if settings.push_requires_allowlist and not parse_hosts(settings.push_allowed_hosts):
        log.warning(
            "SWAHNCHAT_PUSH_ALLOWED_HOSTS is empty: push (UnifiedPush) registration is refused until it lists "
            "the push server's host name"
        )
    alerts = AdminAlerts(settings.admin_ntfy_url, settings.admin_ntfy_token, transport=alert_transport)
    info["dispatcher"] = Dispatcher(hub, push)
    _restore_lockouts(session_factory, limiter)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        push.start()
        alerts.start()
        stop = asyncio.Event()
        task = asyncio.create_task(_maintenance_loop(app, stop)) if settings.background_tasks else None
        log.info("swahnchat started env=%s", settings.env)
        try:
            yield
        finally:
            # Each step is isolated: a failure must not leave the push thread running or the engine open.
            try:
                await hub.close_all(1001, "server restarting")  # clients reconnect at once
            except Exception:
                log.exception("closing websockets failed")
            stop.set()
            if task:
                try:
                    await asyncio.wait_for(task, MAINTENANCE_STOP_TIMEOUT_S)  # lets a running prune finish
                except (TimeoutError, asyncio.CancelledError):
                    log.warning("maintenance did not stop in time; cancelled")
                except Exception:
                    log.exception("maintenance task failed while stopping")
            presence.stop()
            try:
                push.stop()
                alerts.stop()
            finally:
                engine.dispose()

    docs = settings.docs_enabled
    app = FastAPI(
        title="Swahn Chat",
        version=__version__,
        lifespan=lifespan,
        docs_url="/api/v1/docs" if docs else None,
        openapi_url="/api/v1/openapi.json" if docs else None,
        redoc_url=None,
    )
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = session_factory
    app.state.read_session_factory = read_session_factory
    app.state.hub = hub
    app.state.limiter = limiter
    app.state.push = push
    app.state.alerts = alerts
    app.state.last_activity = time.monotonic()  # newest request (the database VACUUM waits for quiet)
    app.state.http_metrics = http_metrics = metrics.HttpMetrics()
    app.state.started_at = time.monotonic()
    app.state.uploads_busy = set()  # upload ids with a chunk being written (single process)
    app.state.presence = presence = Presence(app, settings.presence_debounce_seconds)

    install_error_handlers(app)
    app.add_middleware(BodyLimitMiddleware)

    @app.middleware("http")
    async def request_log(request: Request, call_next):
        start = time.monotonic()
        response = await call_next(request)
        if request.url.path not in ("/healthz", "/readyz"):
            app.state.last_activity = time.monotonic()
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

    app.add_middleware(metrics.MetricsMiddleware, metrics=http_metrics)
    app.add_middleware(SecurityHeadersMiddleware, hsts=settings.hsts)
    app.add_middleware(RequestIdMiddleware)  # added last = outermost: everything below sees the ID

    app.include_router(health.router, include_in_schema=False)  # probes for the container runtime
    app.include_router(metrics.router)  # Prometheus; local or admin only, see app/metrics.py
    api = "/api/v1"
    app.include_router(health.router, prefix=api)
    for module in (auth, admin, users, devices, keys, conversations, messages, calls, sync, attachments, ws):
        app.include_router(module.router, prefix=api)
    install_openapi(app)
    return app


def _restore_lockouts(session_factory, limiter: RateLimiter) -> None:
    """Replay persisted login failures so a restart does not reset the per-username lockouts."""
    try:
        with session_factory() as db:
            restored = lockout.restore(db, limiter)
        if restored:
            log.info("restored %d recent login failures", restored)
    except Exception:  # e.g. the database was not migrated yet: readiness reports that, startup goes on
        log.warning("could not restore login failure counters", exc_info=True)


def _idle(app: FastAPI) -> bool:
    return time.monotonic() - app.state.last_activity >= dbmaint.IDLE_SECONDS


async def _maintenance_loop(app: FastAPI, stop: asyncio.Event) -> None:
    def run() -> None:
        settings = app.state.settings
        failed = False
        try:
            with app.state.session_factory() as db:
                prune(db, settings)
            app.state.limiter.cleanup()
        except Exception:
            failed = True
            log.exception("maintenance failed")
        try:
            with app.state.read_session_factory() as rdb:
                due = dbmaint.is_due(rdb, settings)
            if due:
                with app.state.session_factory() as db:
                    result = dbmaint.run(db, app.state.engine, settings, idle=lambda: _idle(app))
                    failed |= not result["ok"]
        except Exception:
            failed = True
            log.exception("database maintenance failed")
        if failed:
            app.state.alerts.send("maintenance_failed")
        if settings.min_free_disk_mb > 0:
            try:
                if free_disk_bytes(settings.data_dir) < settings.min_free_disk_mb * 1024 * 1024:
                    app.state.alerts.send("low_disk")
            except OSError:
                log.warning("cannot read free disk space of the data directory", exc_info=True)

    while not stop.is_set():
        await asyncio.to_thread(run)
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(stop.wait(), MAINTENANCE_INTERVAL_S)

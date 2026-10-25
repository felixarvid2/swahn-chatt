"""Prometheus metrics in the text exposition format (0.0.4), without a client library: a few counters, one
histogram and gauges read at scrape time. Labels are the HTTP method, the route *template* (never the concrete URL,
so ids do not multiply the series) and the status code. There are no per-user labels anywhere.

Access (`GET /metrics`, outside /api/v1): only from this machine (the connection comes from 127.0.0.1 / ::1 and was
not forwarded by a proxy) or with the bearer token of an administrator; everybody else gets a plain 404."""

import ipaddress
import threading
import time

from fastapi import APIRouter, Request, Response
from sqlalchemy import func, select, text
from starlette.types import ASGIApp, Receive, Scope, Send

from app import __version__
from app.deps import authenticate, bearer_token
from app.errors import ApiError, not_found
from app.models import Event
from app.ratelimit import client_ip
from app.services import stats

# Seconds. Fine enough for a Pi (tens of ms for a message send, seconds for an Argon2 login or an upload).
BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)
CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
METHODS = frozenset({"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
OTHER_METHOD = "OTHER"  # any other (made-up) method: one series, not one per string a client sends
UNMATCHED = "unmatched"  # no route matched (404/405 noise from scanners): one series, not one per URL


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _labels(**labels: str) -> str:
    return "{" + ",".join(f'{k}="{_escape(str(v))}"' for k, v in labels.items()) + "}" if labels else ""


def _num(value: float) -> str:
    return repr(float(value)) if not float(value).is_integer() else str(int(value))


class HttpMetrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counts: dict[tuple[str, str, str], int] = {}
        self._hist: dict[tuple[str, str, str], list[float]] = {}  # per bucket (+Inf last), then [sum]

    def observe(self, method: str, route: str, status: int, seconds: float) -> None:
        key = (method if method in METHODS else OTHER_METHOD, route, str(status))
        with self._lock:
            self._counts[key] = self._counts.get(key, 0) + 1
            h = self._hist.setdefault(key, [0.0] * (len(BUCKETS) + 2))
            for i, bound in enumerate(BUCKETS):
                if seconds <= bound:
                    h[i] += 1
            h[len(BUCKETS)] += 1  # +Inf
            h[len(BUCKETS) + 1] += seconds

    def render(self) -> list[str]:
        with self._lock:
            counts = dict(self._counts)
            hist = {k: list(v) for k, v in self._hist.items()}
        out = [
            "# HELP familychat_http_requests_total HTTP requests by route template and status.",
            "# TYPE familychat_http_requests_total counter",
        ]
        for (method, route, status), n in sorted(counts.items()):
            out.append(f"familychat_http_requests_total{_labels(method=method, route=route, status=status)} {n}")
        out += [
            "# HELP familychat_http_request_duration_seconds Request latency until the response was produced.",
            "# TYPE familychat_http_request_duration_seconds histogram",
        ]
        for (method, route, status), h in sorted(hist.items()):
            base = {"method": method, "route": route, "status": status}
            for bound, n in zip(BUCKETS, h, strict=False):
                out.append(
                    f"familychat_http_request_duration_seconds_bucket{_labels(**base, le=_num(bound))} {_num(n)}"
                )
            out.append(f"familychat_http_request_duration_seconds_bucket{_labels(**base, le='+Inf')} {_num(h[-2])}")
            out.append(f"familychat_http_request_duration_seconds_sum{_labels(**base)} {h[-1]!r}")
            out.append(f"familychat_http_request_duration_seconds_count{_labels(**base)} {_num(h[-2])}")
        return out


def route_template(scope: Scope) -> str:
    """`/api/v1/users/{user_id}/devices` for a matched route, one fixed word for everything else. (A router included
    with a prefix reports only its own part of the path, so the prefix is taken from the concrete path.)"""
    route = scope.get("route")
    path = getattr(route, "path", None)
    if not path:
        return UNMATCHED
    suffix = getattr(route, "path_format", path)
    for name, val in (scope.get("path_params") or {}).items():
        suffix = suffix.replace("{" + name + "}", str(val))
    concrete = scope.get("path", "")
    if suffix and concrete.endswith(suffix):
        return concrete[: len(concrete) - len(suffix)] + path
    return path


class MetricsMiddleware:
    """Pure ASGI (no response buffering, so streamed downloads are not delayed). Time is measured until the response
    starts, which for streamed bodies excludes the transfer itself."""

    def __init__(self, app: ASGIApp, metrics: HttpMetrics):
        self.app = app
        self.metrics = metrics

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        start = time.perf_counter()
        status = 500

        async def wrapped(message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, wrapped)
        finally:
            self.metrics.observe(scope["method"], route_template(scope), status, time.perf_counter() - start)


def _loopback_only(request: Request) -> bool:
    """True for a request made on this machine: the peer is a loopback address and either no proxy forwarded it
    (no X-Forwarded-For / Forwarded / X-Real-IP header) or the peer is a configured trusted proxy whose forwarded
    client is loopback too."""
    peer = request.client.host if request.client else ""
    forwarded = any(h in request.headers for h in ("x-forwarded-for", "forwarded", "x-real-ip"))
    trusted = request.app.state.settings.trusted_networks
    try:
        peer_ip = ipaddress.ip_address(peer)
    except ValueError:
        return False
    if forwarded and not any(peer_ip in net for net in trusted):
        return False  # a proxy we do not know passed this on: the real client is not visible
    resolved = client_ip(request, trusted)
    try:
        return ipaddress.ip_address(resolved).is_loopback
    except ValueError:
        return False


def _allowed(request: Request) -> bool:
    if _loopback_only(request):
        return True
    try:
        principal = authenticate(request.app, bearer_token(request))
    except ApiError:
        return False
    return principal.is_admin


def gauges(app) -> list[str]:
    settings = app.state.settings
    values: list[tuple[str, str, str, float | None]] = [
        ("familychat_websocket_connections", "gauge", "Open WebSocket connections.", app.state.hub.connected_count()),
        (
            "familychat_push_queue_depth",
            "gauge",
            "Push notifications waiting to be sent.",
            app.state.push.pending_count(),
        ),
        (
            "familychat_uptime_seconds",
            "gauge",
            "Seconds since the server started.",
            time.monotonic() - app.state.started_at,
        ),
    ]
    with app.state.read_session_factory() as db:
        values.append(
            (
                "familychat_event_queue_depth",
                "gauge",
                "Unacknowledged sync events over all devices.",
                db.scalar(select(func.count()).select_from(Event)) or 0,
            )
        )
        db_bytes = stats.db_file_bytes(settings)
        if db_bytes is None and db.bind.dialect.name == "postgresql":
            db_bytes = db.scalar(text("SELECT pg_database_size(current_database())"))
    values.append(
        ("familychat_database_size_bytes", "gauge", "Database size (SQLite file with WAL, or PostgreSQL).", db_bytes)
    )
    try:
        free = stats.free_disk_bytes(settings.data_dir)
    except OSError:
        free = None
    values.append(("familychat_disk_free_bytes", "gauge", "Free space on the data volume.", free))
    out = []
    for name, kind, help_, value in values:
        if value is None:
            continue
        out += [f"# HELP {name} {help_}", f"# TYPE {name} {kind}", f"{name} {_num(value)}"]
    out += [
        "# HELP familychat_build_info Server version.",
        "# TYPE familychat_build_info gauge",
        f"familychat_build_info{_labels(version=__version__)} 1",
    ]
    return out


router = APIRouter()


@router.get("/metrics", include_in_schema=False)
def metrics(request: Request) -> Response:
    if not _allowed(request):
        raise not_found()
    lines = request.app.state.http_metrics.render() + gauges(request.app)
    return Response("\n".join(lines) + "\n", media_type=CONTENT_TYPE, headers={"Cache-Control": "no-store"})

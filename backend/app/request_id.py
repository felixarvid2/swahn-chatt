"""Request IDs: X-Request-ID is accepted from the client only in a safe shape, otherwise generated. The ID is
returned in the response header, added to every JSON log line of the request and to error bodies."""

import re
import uuid
from contextvars import ContextVar

from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

HEADER = "X-Request-ID"
VALID_RE = re.compile(r"^[A-Za-z0-9._-]{8,64}$")

_current: ContextVar[str | None] = ContextVar("familychat_request_id", default=None)


def current_request_id() -> str | None:
    return _current.get()


def new_request_id() -> str:
    return uuid.uuid4().hex


def pick_request_id(supplied: str | None) -> str:
    """The client's value if it matches ^[A-Za-z0-9._-]{8,64}$ (no log injection, bounded size), else a new one."""
    if supplied is not None and VALID_RE.fullmatch(supplied):
        return supplied
    return new_request_id()


class RequestIdMiddleware:
    """Pure ASGI (not BaseHTTPMiddleware) so the contextvar reaches the endpoint, its threadpool and the log
    calls. Installed outermost, so errors produced by the other middlewares carry the ID too."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        supplied = None
        for name, value in scope["headers"]:
            if name == b"x-request-id":
                supplied = value.decode("latin-1")
                break
        request_id = pick_request_id(supplied)
        scope.setdefault("state", {})["request_id"] = request_id
        token = _current.set(request_id)

        async def send_with_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message)[HEADER] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_with_id)
        finally:
            _current.reset(token)

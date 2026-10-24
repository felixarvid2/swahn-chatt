"""API errors: every non-2xx body is {"code", "message", ...extras}."""

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("familychat.errors")


class ApiError(Exception):
    def __init__(
        self,
        status: int,
        code: str,
        message: str = "",
        extra: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ):
        self.status = status
        self.code = code
        self.message = message or code.replace("_", " ")
        self.extra = extra or {}
        self.headers = headers
        super().__init__(self.message)


def not_found(what: str = "not found") -> ApiError:
    return ApiError(404, "not_found", what)


def forbidden(message: str = "forbidden") -> ApiError:
    return ApiError(403, "forbidden", message)


def conflict(message: str = "conflict", code: str = "conflict", **extra: Any) -> ApiError:
    return ApiError(409, code, message, extra)


def invalid(message: str) -> ApiError:
    return ApiError(422, "invalid_request", message)


def unauthorized(message: str = "authentication required", code: str = "unauthorized") -> ApiError:
    return ApiError(401, code, message, headers={"WWW-Authenticate": "Bearer"})


_STATUS_CODES = {
    400: "invalid_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "conflict",
    413: "payload_too_large",
    415: "unsupported_media_type",
    416: "range_not_satisfiable",
    429: "rate_limited",
}


def _body(code: str, message: str, extra: dict[str, Any] | None = None) -> dict[str, Any]:
    body = {"code": code, "message": message}
    if extra:
        body.update(extra)
    return body


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(_body(exc.code, exc.message, exc.extra), exc.status, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        # Never echo the submitted input (it may be a password); only where and why.
        problems = [
            {"loc": [str(p) for p in e.get("loc", ()) if p != "body"], "msg": str(e.get("msg", ""))}
            for e in exc.errors()
        ]
        first = problems[0] if problems else {"loc": [], "msg": "invalid"}
        where = ".".join(first["loc"]) or "request"
        return JSONResponse(_body("invalid_request", f"{where}: {first['msg']}", {"errors": problems[:10]}), 422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _STATUS_CODES.get(exc.status_code, "error")
        return JSONResponse(
            _body(code, str(exc.detail) if exc.detail else code.replace("_", " ")),
            exc.status_code,
            headers=getattr(exc, "headers", None),
        )

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled error on %s %s", request.method, request.url.path, exc_info=exc)
        return JSONResponse(_body("internal_error", "internal server error"), 500)

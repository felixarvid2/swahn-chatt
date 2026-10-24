"""API errors: every non-2xx body is {"code", "message", ...extras}."""

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import DataError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.request_id import HEADER as REQUEST_ID_HEADER
from app.request_id import current_request_id

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


def error_body(
    code: str, message: str, extra: dict[str, Any] | None = None, request_id: str | None = None
) -> dict[str, Any]:
    body = {"code": code, "message": message}
    if extra:
        body.update(extra)
    request_id = request_id or current_request_id()
    if request_id:
        body["request_id"] = request_id
    return body


def error_responses(*statuses: int) -> dict[int | str, dict[str, Any]]:
    """`responses=` entries for OpenAPI: the {"code","message"} error model for each status."""
    from app.schemas import ErrorOut

    return {s: {"model": ErrorOut, "description": ERROR_DESCRIPTIONS[s]} for s in statuses}


ERROR_DESCRIPTIONS = {
    401: "Missing, invalid or expired credentials (`unauthorized`, `token_expired`, `invalid_credentials`, ...).",
    403: "Not allowed (`forbidden`).",
    404: "Not found, or not visible to the caller (`not_found`).",
    409: "Conflicts with the current state (`conflict` and more specific codes).",
    413: "Body too large (`payload_too_large`, `quota_exceeded`).",
    415: "Unsupported media type (`unsupported_media_type`).",
    422: "Invalid request (`invalid_request`).",
    429: "Rate limited (`rate_limited`); see the `Retry-After` header.",
}


def _rid_fields(rid: str | None) -> dict[str, Any]:
    return {"fields": {"request_id": rid}} if rid else {}


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(error_body(exc.code, exc.message, exc.extra), exc.status, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        # Never echo the submitted input (it may be a password); only where and why.
        problems = [
            {"loc": [str(p) for p in e.get("loc", ()) if p != "body"], "msg": str(e.get("msg", ""))}
            for e in exc.errors()
        ]
        first = problems[0] if problems else {"loc": [], "msg": "invalid"}
        where = ".".join(first["loc"]) or "request"
        return JSONResponse(error_body("invalid_request", f"{where}: {first['msg']}", {"errors": problems[:10]}), 422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _STATUS_CODES.get(exc.status_code, "error")
        return JSONResponse(
            error_body(code, str(exc.detail) if exc.detail else code.replace("_", " ")),
            exc.status_code,
            headers=getattr(exc, "headers", None),
        )

    @app.exception_handler(DataError)
    async def _data_error(_: Request, exc: DataError) -> JSONResponse:
        # PostgreSQL rejects values SQLite stores happily (NUL characters, out of range numbers). Never echo them.
        log.warning("database rejected a value: %s", type(exc.orig).__name__)
        return JSONResponse(error_body("invalid_request", "a submitted value cannot be stored"), 422)

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        # Runs outside the request ID middleware (ServerErrorMiddleware): the ID comes from the scope, and the
        # header has to be added here.
        rid = request.scope.get("state", {}).get("request_id")
        log.error("unhandled error on %s %s", request.method, request.url.path, exc_info=exc, extra=_rid_fields(rid))
        headers = {REQUEST_ID_HEADER: rid} if rid else None
        return JSONResponse(error_body("internal_error", "internal server error", request_id=rid), 500, headers=headers)

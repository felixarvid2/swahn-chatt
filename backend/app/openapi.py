"""OpenAPI polish: validation errors use the same {"code","message"} model as every other error, and the bearer
scheme is declared for the endpoints that need it."""

from typing import Any

from fastapi import FastAPI

from app.errors import ERROR_DESCRIPTIONS

# Endpoints reachable without an access token (their 401 means "wrong credentials", not "missing token").
PUBLIC_OPERATIONS = {
    ("post", "/api/v1/auth/invite/redeem"),
    ("post", "/api/v1/auth/login"),
    ("post", "/api/v1/auth/refresh"),
    ("post", "/api/v1/auth/password/reset"),
}

DESCRIPTION = (
    "FamilyChat backend: an end-to-end encrypted message mailbox. The server stores ciphertext and routing "
    'metadata only. Every error response is `{"code", "message", "request_id"?}`; the contract with the '
    "clients is `docs/API.md`."
)


def install_openapi(app: FastAPI) -> None:
    def build() -> dict[str, Any]:
        if app.openapi_schema:
            return app.openapi_schema
        schema = FastAPI.openapi(app)  # builds and caches the stock schema
        components = schema.setdefault("components", {})
        schemas = components.setdefault("schemas", {})
        error_ref = {"$ref": "#/components/schemas/ErrorOut"}
        for path, operations in schema["paths"].items():
            for method, op in operations.items():
                resp = op["responses"].get("422")
                if resp is not None:
                    resp["description"] = ERROR_DESCRIPTIONS[422]
                    resp["content"] = {"application/json": {"schema": error_ref}}
                if "401" in op["responses"] and (method, path) not in PUBLIC_OPERATIONS:
                    op["security"] = [{"bearerAuth": []}]
        schemas.pop("HTTPValidationError", None)
        schemas.pop("ValidationError", None)
        components["securitySchemes"] = {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}
        schema["info"]["description"] = DESCRIPTION
        return schema

    app.openapi = build  # type: ignore[method-assign]

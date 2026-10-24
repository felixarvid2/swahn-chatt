"""The generated OpenAPI document is complete and valid: tags, summaries, documented errors, response models."""

import pytest
from fastapi.routing import APIRoute
from openapi_spec_validator import validate

from app.openapi import PUBLIC_OPERATIONS
from app.routers import admin, attachments, auth, conversations, devices, health, keys, messages, sync, users

HTTP_METHODS = {"get", "put", "post", "delete", "patch"}


@pytest.fixture
def spec(app):
    return app.openapi()


def operations(spec):
    for path, item in spec["paths"].items():
        for method, op in item.items():
            if method in HTTP_METHODS:
                yield method, path, op


def ref_name(response) -> str | None:
    schema = response.get("content", {}).get("application/json", {}).get("schema", {})
    return schema.get("$ref", "").rsplit("/", 1)[-1] or None


def test_schema_is_a_valid_openapi_document(spec):
    validate(spec)
    assert spec["openapi"].startswith("3.")
    assert spec["info"]["title"] == "FamilyChat" and spec["info"]["description"]


def test_served_document_is_the_same_valid_document(client, spec):
    r = client.get("/api/v1/openapi.json")
    assert r.status_code == 200 and r.json() == spec
    validate(r.json())


def test_every_operation_has_a_tag_and_a_real_summary(spec):
    ops = list(operations(spec))
    assert len(ops) >= 40
    for method, path, op in ops:
        where = f"{method.upper()} {path}"
        assert op.get("tags"), where
        summary = op.get("summary", "")
        assert summary and summary == summary.strip(), where
        # FastAPI's fallback is the function name in Title Case, e.g. "Get Sync": it must be written out instead
        assert summary != op["operationId"].split("_api_v1")[0].replace("_", " ").title(), where
        assert op["responses"], where
        assert any(code.startswith("2") for code in op["responses"]), where


def test_summaries_are_unique_per_tag(spec):
    seen = {}
    for method, path, op in operations(spec):
        key = (op["tags"][0], op["summary"])
        assert key not in seen, f"{method} {path} repeats the summary of {seen[key]}"
        seen[key] = f"{method} {path}"


def test_documented_errors_use_the_code_message_model(spec):
    schemas = spec["components"]["schemas"]
    assert set(schemas["ErrorOut"]["required"]) == {"code", "message"}
    assert "HTTPValidationError" not in schemas and "ValidationError" not in schemas
    for method, path, op in operations(spec):
        for status, response in op["responses"].items():
            if status[0] in "45" and status not in {"416", "503"}:
                assert ref_name(response) == "ErrorOut", f"{method.upper()} {path} {status}"
                assert response["description"]


def test_error_statuses_are_documented_where_they_apply(spec):
    for method, path, op in operations(spec):
        where = f"{method.upper()} {path}"
        codes = set(op["responses"])
        public = (method, path) in PUBLIC_OPERATIONS or path.startswith("/api/v1/health") or "readyz" in path
        if not public and "health" not in op["tags"]:
            assert "401" in codes and op.get("security") == [{"bearerAuth": []}], where
        if "/admin/" in path:
            assert "403" in codes, where
        has_params = "{" in path or op.get("parameters") or op.get("requestBody")
        if has_params:
            assert "422" in codes, where
        if op.get("requestBody"):
            assert "413" in codes, where
        if "{" in path and path != "/api/v1/attachments/{attachment_id}" and "conversations" not in path:
            assert "404" in codes or method == "get", where


@pytest.mark.parametrize(
    ("method", "path", "expected"),
    [
        ("post", "/api/v1/auth/login", {"401", "409", "422", "429"}),
        ("post", "/api/v1/auth/invite/redeem", {"404", "409", "422", "429"}),
        ("post", "/api/v1/auth/password/reset", {"404", "409", "422", "429"}),
        ("post", "/api/v1/messages", {"403", "404", "409", "413", "422"}),
        ("patch", "/api/v1/conversations/{conversation_id}", {"403", "404", "409", "422"}),
        ("post", "/api/v1/attachments", {"401", "413", "415", "422"}),
        ("get", "/api/v1/attachments/{attachment_id}", {"404", "416"}),
        ("post", "/api/v1/admin/users/{user_id}/password-reset", {"403", "404"}),
        ("delete", "/api/v1/admin/users/{user_id}", {"404", "409"}),
        ("get", "/api/v1/keys/{user_id}", {"404", "429"}),
        ("put", "/api/v1/users/me/password", {"403", "429"}),
    ],
)
def test_spot_checks(spec, method, path, expected):
    assert expected <= set(spec["paths"][path][method]["responses"])


def test_every_json_route_declares_a_response_model():
    routes = [
        r
        for module in (auth, admin, users, devices, keys, conversations, messages, sync, attachments, health)
        for r in module.router.routes
        if isinstance(r, APIRoute) and r.include_in_schema
    ]
    assert len(routes) >= 40
    for route in routes:
        where = f"{sorted(route.methods)} {route.path}"
        if route.status_code == 204:
            assert route.response_model is None, where
        elif getattr(route.response_class, "__name__", "") == "FileResponse":
            continue  # the attachment download streams bytes
        else:
            assert route.response_model is not None, where


def test_204_operations_have_no_body_schema(spec):
    for method, path, op in operations(spec):
        if "204" in op["responses"]:
            assert "content" not in op["responses"]["204"], f"{method} {path}"


def test_new_message_kinds_and_fields_are_documented(spec):
    schemas = spec["components"]["schemas"]
    assert "reaction" in schemas["SendMessage"]["properties"]["kind"]["enum"]
    assert "reaction" in schemas["MessageOut"]["properties"]["kind"]["enum"]
    assert "expires_at" in schemas["MessageOut"]["properties"]
    assert "disappearing_seconds" in schemas["ConversationOut"]["properties"]
    assert "disappearing_seconds" in schemas["ConversationUpdate"]["properties"]
    # additive: the fields older clients know are all still required, the new ones are optional in responses
    assert "expires_at" not in schemas["MessageOut"]["required"]
    assert "disappearing_seconds" not in schemas["ConversationOut"]["required"]


def test_health_probes_are_only_listed_under_the_api_prefix(spec):
    assert "/healthz" not in spec["paths"] and "/api/v1/healthz" in spec["paths"]

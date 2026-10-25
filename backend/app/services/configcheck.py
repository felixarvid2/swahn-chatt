"""`cli check-config`: the effective settings (never secrets) and what looks wrong with them."""

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from sqlalchemy.engine import make_url

from app.config import Settings, legacy_env_message, legacy_env_vars
from app.services.push import parse_hosts
from app.services.stats import free_disk_bytes

MIN_SECRET_BYTES = 32


@dataclass
class ConfigReport:
    settings: list[tuple[str, str]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def redacted_database_url(url: str) -> str:
    """The URL without the password (`postgresql+psycopg://user:***@host/db`)."""
    try:
        return make_url(url).render_as_string(hide_password=True)
    except Exception:
        return "(unparseable)"


def _existing_ancestor(path: Path) -> Path:
    path = path.resolve()
    while not path.exists() and path != path.parent:
        path = path.parent
    return path


def check(settings: Settings, secret_key_bytes: int) -> ConfigReport:
    """`secret_key_bytes` is the length of the configured SWAHNCHAT_SECRET_KEY (0 when unset): the Settings object
    may hold an ephemeral replacement, which must not hide the problem."""
    r = ConfigReport()
    prod = settings.env == "production"
    hosts = sorted(parse_hosts(settings.push_allowed_hosts))
    r.settings = [
        ("env", settings.env),
        ("secret_key", f"{secret_key_bytes} bytes" if secret_key_bytes else "not set"),
        ("data_dir", str(settings.data_dir)),
        ("database_url", redacted_database_url(settings.database_url)),
        ("attachments_dir", str(settings.attachments_dir)),
        ("max_attachment_mb", str(settings.max_attachment_mb)),
        ("user_quota_mb", str(settings.user_quota_mb)),
        ("access_token_minutes", str(settings.access_token_minutes)),
        ("refresh_token_days", str(settings.refresh_token_days)),
        ("max_devices_per_user", str(settings.max_devices_per_user)),
        ("trusted_proxies", settings.trusted_proxies or "(none)"),
        ("behind_proxy", str(settings.behind_proxy).lower()),
        ("push_allowed_hosts", ", ".join(hosts) or "(none)"),
        ("push_allow_http", str(settings.push_allow_http).lower()),
        ("expose_docs", str(settings.expose_docs).lower()),
        ("log_level", settings.log_level),
        ("rate_limit_enabled", str(settings.rate_limit_enabled).lower()),
        ("envelope_retention_days", str(settings.envelope_retention_days)),
        ("audit_retention_days", str(settings.audit_retention_days)),
        ("min_free_disk_mb", str(settings.min_free_disk_mb)),
        ("hsts", str(settings.hsts).lower()),
        ("db_maintenance_days", str(settings.db_maintenance_days)),
        ("call_retention_seconds", str(settings.call_retention_seconds)),
        (
            "turn",
            f"{len(settings.turn_url_list)} url(s)" if settings.turn_url_list else "off",
        ),  # the secret is never printed
        ("admin_alerts", "on" if settings.admin_ntfy_url else "off"),  # the topic URL itself is never printed
    ]

    legacy = legacy_env_vars()
    if legacy:
        r.errors.append(legacy_env_message(legacy))

    if secret_key_bytes < MIN_SECRET_BYTES:
        problem = (
            f"SWAHNCHAT_SECRET_KEY is only {secret_key_bytes} bytes (needs {MIN_SECRET_BYTES})"
            if secret_key_bytes
            else f"SWAHNCHAT_SECRET_KEY is not set (needs {MIN_SECRET_BYTES} bytes)"
        )
        if prod:
            r.errors.append(problem + " (the server refuses to start in production)")
        else:
            r.warnings.append(problem + " (development uses an ephemeral key, tokens die on restart)")

    try:
        networks = settings.trusted_networks
    except ValueError as exc:
        r.errors.append(f"SWAHNCHAT_TRUSTED_PROXIES is not a list of CIDRs: {exc}")
    else:
        if settings.behind_proxy and not networks:
            r.warnings.append(
                "SWAHNCHAT_BEHIND_PROXY is set but SWAHNCHAT_TRUSTED_PROXIES is empty: every client appears with "
                "the proxy's address, so rate limits and the audit log's IPs are shared"
            )
        for net in networks:
            if net.prefixlen == 0:
                r.warnings.append(
                    f"trusted proxy {net} trusts every address: anyone can forge X-Forwarded-For and dodge rate limits"
                )

    if prod and not hosts:
        r.warnings.append(
            "production without SWAHNCHAT_PUSH_ALLOWED_HOSTS: push registration is refused (409 push_not_configured)"
        )
    if prod and settings.push_allow_http:
        r.warnings.append("SWAHNCHAT_PUSH_ALLOW_HTTP is true in production: push endpoints may use plain http")
    if prod and settings.expose_docs:
        r.warnings.append("SWAHNCHAT_EXPOSE_DOCS is true in production: /api/v1/docs and openapi.json are public")
    if prod and not settings.rate_limit_enabled:
        r.warnings.append("rate limiting is disabled in production")

    root = _existing_ancestor(settings.data_dir)
    if not root.is_dir():
        r.errors.append(f"data directory {settings.data_dir} cannot be created: {root} is not a directory")
    elif not os.access(root, os.W_OK):
        r.errors.append(f"data directory {settings.data_dir} is not writable (nearest existing folder: {root})")
    try:
        free_mb = free_disk_bytes(root) // (1024 * 1024)
        r.settings.append(("disk_free_mb", str(free_mb)))
        if settings.min_free_disk_mb and free_mb < settings.min_free_disk_mb:
            r.warnings.append(
                f"low disk: {free_mb} MiB free on the data volume, below SWAHNCHAT_MIN_FREE_DISK_MB="
                f"{settings.min_free_disk_mb} (/readyz reports 503 low_disk)"
            )
    except OSError as exc:
        r.warnings.append(f"cannot read free disk space: {exc}")

    if re.match(r"postgres(ql)?(\+\w+)?://", settings.database_url) and not settings.database_url.startswith(
        "postgresql+psycopg://"
    ):
        r.errors.append("a PostgreSQL SWAHNCHAT_DATABASE_URL must start with postgresql+psycopg://")
    return r

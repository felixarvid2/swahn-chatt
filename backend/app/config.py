"""Settings from FAMILYCHAT_* environment variables."""

import ipaddress
import logging
import secrets
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

log = logging.getLogger("familychat.config")


class ConfigError(RuntimeError):
    pass


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="FAMILYCHAT_", extra="ignore")

    env: Literal["development", "production"] = "production"
    secret_key: str = ""
    data_dir: Path = Path("./data")
    database_url: str = ""
    attachments_dir: Path | None = None
    max_attachment_mb: int = Field(100, ge=1)
    user_quota_mb: int = Field(2048, ge=1)
    access_token_minutes: int = Field(15, ge=1)
    refresh_token_days: int = Field(60, ge=1)
    # Comma separated CIDRs whose X-Forwarded-For header is honoured (rate limiting only).
    trusted_proxies: str = ""
    push_allow_http: bool = False
    # Comma separated host names push endpoints may use (e.g. "ntfy.example.ts.net"). Required in production:
    # empty means push registration is refused there (development: any host name, but never IP literals).
    push_allowed_hosts: str = ""
    expose_docs: bool = False
    log_level: str = "INFO"
    # Active devices per account; logging in on another one fails with 409 device_limit until one is removed.
    max_devices_per_user: int = Field(10, ge=1)
    # How often an open WebSocket re-checks that its session and device are still valid (covers revocations done
    # by another process, e.g. the `cli` commands, which cannot kick sockets in this process directly).
    ws_revalidate_seconds: float = Field(60.0, ge=0.05)
    # Tunables (defaults match SECURITY.md; tests lower them).
    rate_limit_enabled: bool = True
    argon2_time_cost: int = Field(3, ge=1)
    argon2_memory_kb: int = Field(65536, ge=8)
    argon2_parallelism: int = Field(4, ge=1)
    background_tasks: bool = True
    # Housekeeping (hourly job): delivered ciphertext is useless after delivery; audit rows are kept this long.
    envelope_retention_days: int = Field(30, ge=1)
    audit_retention_days: int = Field(180, ge=1)
    # /readyz answers 503 "low_disk" when the data volume has less free space than this (0 disables the check).
    min_free_disk_mb: int = Field(200, ge=0)

    @model_validator(mode="after")
    def _finish(self) -> "Settings":
        if len(self.secret_key.encode()) < 32:
            if self.env == "production":
                raise ConfigError(
                    "FAMILYCHAT_SECRET_KEY must be set to at least 32 bytes in production "
                    "(generate one with: openssl rand -base64 48)"
                )
            if self.secret_key:
                log.warning("FAMILYCHAT_SECRET_KEY is shorter than 32 bytes; using an ephemeral key")
            else:
                log.warning("FAMILYCHAT_SECRET_KEY not set: using an ephemeral key (tokens die on restart)")
            self.secret_key = secrets.token_urlsafe(48)
        if not self.database_url:
            self.database_url = f"sqlite:///{(self.data_dir / 'familychat.db').resolve()}"
        if self.attachments_dir is None:
            self.attachments_dir = self.data_dir / "attachments"
        return self

    @property
    def push_requires_allowlist(self) -> bool:
        return self.env == "production"

    @property
    def docs_enabled(self) -> bool:
        return self.env == "development" or self.expose_docs

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    @property
    def sqlite_path(self) -> Path:
        """Filesystem path of the SQLite database (only valid when is_sqlite)."""
        from sqlalchemy.engine import make_url

        db = make_url(self.database_url).database
        if not db or db == ":memory:":
            raise ConfigError("this operation needs a file based SQLite database")
        return Path(db)

    @property
    def max_attachment_bytes(self) -> int:
        return self.max_attachment_mb * 1024 * 1024

    @property
    def user_quota_bytes(self) -> int:
        return self.user_quota_mb * 1024 * 1024

    @property
    def trusted_networks(self) -> list[ipaddress._BaseNetwork]:
        nets = []
        for part in self.trusted_proxies.split(","):
            part = part.strip()
            if part:
                nets.append(ipaddress.ip_network(part, strict=False))
        return nets


@lru_cache
def get_settings() -> Settings:
    return Settings()

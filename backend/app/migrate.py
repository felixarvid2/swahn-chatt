"""Run Alembic programmatically (CLI, tests). The container entrypoint calls `alembic upgrade head` directly."""

from functools import lru_cache
from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Engine

from app.config import Settings

BACKEND_DIR = Path(__file__).resolve().parent.parent


def alembic_config(database_url: str) -> Config:
    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND_DIR / "migrations"))
    cfg.set_main_option("sqlalchemy.url", database_url.replace("%", "%%"))
    return cfg


def upgrade(settings: Settings, revision: str = "head") -> None:
    command.upgrade(alembic_config(settings.database_url), revision)


@lru_cache
def code_head() -> str:
    """The newest Alembic revision this code ships."""
    head = ScriptDirectory.from_config(alembic_config("sqlite://")).get_current_head()
    assert head is not None
    return head


def db_revision(engine: Engine) -> str | None:
    """The revision the database is at (None when it was never migrated)."""
    with engine.connect() as conn:
        return MigrationContext.configure(conn).get_current_revision()

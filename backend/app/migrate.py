"""Run Alembic programmatically (CLI, tests). The container entrypoint calls `alembic upgrade head` directly."""

from pathlib import Path

from alembic import command
from alembic.config import Config

from app.config import Settings

BACKEND_DIR = Path(__file__).resolve().parent.parent


def alembic_config(database_url: str) -> Config:
    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND_DIR / "migrations"))
    cfg.set_main_option("sqlalchemy.url", database_url.replace("%", "%%"))
    return cfg


def upgrade(settings: Settings, revision: str = "head") -> None:
    command.upgrade(alembic_config(settings.database_url), revision)

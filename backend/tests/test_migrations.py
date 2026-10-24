import sqlite3

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect

from app.migrate import alembic_config
from app.models import Base
from tests.conftest import make_settings


def tables(path):
    con = sqlite3.connect(path)
    try:
        return {
            r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
        }
    finally:
        con.close()


def test_upgrade_head_on_empty_database(tmp_path):
    s = make_settings(tmp_path)
    cfg = alembic_config(s.database_url)
    command.upgrade(cfg, "head")
    assert tables(tmp_path / "familychat.db") == set(Base.metadata.tables) | {"alembic_version"}
    con = sqlite3.connect(tmp_path / "familychat.db")
    assert con.execute("SELECT value FROM counters WHERE name='message_seq'").fetchone() == (0,)
    assert (
        con.execute("SELECT version_num FROM alembic_version").fetchone()[0]
        == ScriptDirectory.from_config(cfg).get_current_head()
    )
    con.close()
    command.upgrade(cfg, "head")  # idempotent


def test_single_migration_head():
    heads = ScriptDirectory.from_config(alembic_config("sqlite:///x.db")).get_heads()
    assert len(heads) == 1


def test_models_match_migrations(tmp_path):
    """`alembic revision --autogenerate` would produce nothing: the models and the migrations agree."""
    s = make_settings(tmp_path)
    command.upgrade(alembic_config(s.database_url), "head")
    engine = create_engine(s.database_url)
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn, opts={"compare_type": True}), Base.metadata)
    engine.dispose()
    assert diff == []


def test_unique_and_index_constraints_exist(tmp_path):
    s = make_settings(tmp_path)
    command.upgrade(alembic_config(s.database_url), "head")
    engine = create_engine(s.database_url)
    insp = inspect(engine)
    assert any(u["column_names"] == ["seq"] for u in insp.get_unique_constraints("messages")) or any(
        i["column_names"] == ["seq"] and i["unique"] for i in insp.get_indexes("messages")
    )
    assert any(u["column_names"] == ["user_id", "number"] for u in insp.get_unique_constraints("devices"))
    assert any(i["column_names"] == ["device_id", "seq"] for i in insp.get_indexes("events"))
    engine.dispose()
    con = sqlite3.connect(tmp_path / "familychat.db")
    sql = con.execute("SELECT sql FROM sqlite_master WHERE name='events'").fetchone()[0]
    assert "AUTOINCREMENT" in sql.upper()  # cursors are never reused after deletes
    con.close()


def test_downgrade_to_base_and_back(tmp_path):
    s = make_settings(tmp_path)
    cfg = alembic_config(s.database_url)
    command.upgrade(cfg, "head")
    command.downgrade(cfg, "base")
    assert tables(tmp_path / "familychat.db") <= {"alembic_version"}
    command.upgrade(cfg, "head")
    assert "messages" in tables(tmp_path / "familychat.db")


def test_sqlite_pragmas(app):
    with app.state.engine.connect() as conn:
        raw = conn.connection.dbapi_connection
        assert raw.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        assert raw.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        assert raw.execute("PRAGMA busy_timeout").fetchone()[0] >= 5000

import sqlite3
import uuid

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url
from sqlalchemy.pool import NullPool

from app.migrate import alembic_config
from app.models import Base
from tests.conftest import PG_ADMIN_URL, make_settings


def tables(path):
    con = sqlite3.connect(path)
    try:
        return {
            r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
        }
    finally:
        con.close()


@pytest.fixture
def blank_db(tmp_path):
    """URL of an empty database: a SQLite file, or a new empty PostgreSQL database when the suite runs on PostgreSQL."""
    if not PG_ADMIN_URL:
        yield make_settings(tmp_path).database_url
        return
    name = f"fc_mig_{uuid.uuid4().hex[:10]}"
    admin = create_engine(PG_ADMIN_URL, isolation_level="AUTOCOMMIT", poolclass=NullPool)
    with admin.connect() as c:
        c.execute(text(f'CREATE DATABASE "{name}"'))
    yield make_url(PG_ADMIN_URL).set(database=name).render_as_string(hide_password=False)
    with admin.connect() as c:
        c.execute(text(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))
    admin.dispose()


def table_names(url) -> set[str]:
    engine = create_engine(url, poolclass=NullPool)
    try:
        return set(inspect(engine).get_table_names())
    finally:
        engine.dispose()


def scalar(url, sql):
    engine = create_engine(url, poolclass=NullPool)
    try:
        with engine.connect() as conn:
            return conn.execute(text(sql)).scalar()
    finally:
        engine.dispose()


def test_upgrade_head_on_empty_database(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "head")
    assert table_names(blank_db) == set(Base.metadata.tables) | {"alembic_version"}
    assert scalar(blank_db, "SELECT value FROM counters WHERE name='message_seq'") == 0
    assert (
        scalar(blank_db, "SELECT version_num FROM alembic_version")
        == ScriptDirectory.from_config(cfg).get_current_head()
    )
    command.upgrade(cfg, "head")  # idempotent


def test_single_migration_head():
    heads = ScriptDirectory.from_config(alembic_config("sqlite:///x.db")).get_heads()
    assert len(heads) == 1


def test_models_match_migrations(blank_db):
    """`alembic revision --autogenerate` would produce nothing: the models and the migrations agree."""
    command.upgrade(alembic_config(blank_db), "head")
    engine = create_engine(blank_db)
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn, opts={"compare_type": True}), Base.metadata)
    engine.dispose()
    assert diff == []


def test_unique_and_index_constraints_exist(blank_db):
    command.upgrade(alembic_config(blank_db), "head")
    engine = create_engine(blank_db)
    insp = inspect(engine)
    assert any(u["column_names"] == ["seq"] for u in insp.get_unique_constraints("messages")) or any(
        i["column_names"] == ["seq"] and i["unique"] for i in insp.get_indexes("messages")
    )
    assert any(u["column_names"] == ["user_id", "number"] for u in insp.get_unique_constraints("devices"))
    assert any(i["column_names"] == ["device_id", "seq"] for i in insp.get_indexes("events"))
    assert any(i["column_names"] == ["expires_at"] for i in insp.get_indexes("messages"))
    assert any(u["column_names"] == ["code_hash"] for u in insp.get_unique_constraints("password_resets"))
    engine.dispose()
    if blank_db.startswith("sqlite"):
        sql = scalar(blank_db, "SELECT sql FROM sqlite_master WHERE name='events'")
        assert "AUTOINCREMENT" in sql.upper()  # cursors are never reused after deletes


def test_downgrade_to_base_and_back(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "head")
    command.downgrade(cfg, "base")
    assert table_names(blank_db) <= {"alembic_version"}
    command.upgrade(cfg, "head")
    assert "messages" in table_names(blank_db)


def test_0004_and_0005_add_and_drop_their_columns(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "0003")
    assert "password_resets" not in table_names(blank_db)
    command.upgrade(cfg, "head")
    engine = create_engine(blank_db)
    insp = inspect(engine)
    assert "password_resets" in insp.get_table_names()
    assert "disappearing_seconds" in {c["name"] for c in insp.get_columns("conversations")}
    assert "expires_at" in {c["name"] for c in insp.get_columns("messages")}
    engine.dispose()
    command.downgrade(cfg, "0003")
    engine = create_engine(blank_db)
    insp = inspect(engine)
    assert "password_resets" not in insp.get_table_names()
    assert "disappearing_seconds" not in {c["name"] for c in insp.get_columns("conversations")}
    assert "expires_at" not in {c["name"] for c in insp.get_columns("messages")}
    engine.dispose()


@pytest.mark.sqlite_only
def test_sqlite_pragmas(app):
    with app.state.engine.connect() as conn:
        raw = conn.connection.dbapi_connection
        assert raw.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        assert raw.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        assert raw.execute("PRAGMA busy_timeout").fetchone()[0] >= 5000


def execute(url, *statements):
    engine = create_engine(url, poolclass=NullPool)
    try:
        with engine.begin() as conn:
            for sql in statements:
                conn.execute(text(sql))
    finally:
        engine.dispose()


def rows(url, sql):
    engine = create_engine(url, poolclass=NullPool)
    try:
        with engine.connect() as conn:
            return {tuple(r) for r in conn.execute(text(sql))}
    finally:
        engine.dispose()


def test_0002_backfills_token_history_and_downgrades(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "0001")
    execute(
        blank_db,
        "INSERT INTO users (id, username, display_name, password_hash, is_admin, created_at) "
        "VALUES ('u','u','U','x',false,1)",
        "INSERT INTO devices (id, user_id, number, name, created_at, last_seen_at, acked_cursor) "
        "VALUES ('d','u',1,'n',1,1,0)",
        "INSERT INTO sessions "
        "(id, user_id, device_id, refresh_token_hash, previous_token_hash, created_at, expires_at) "
        "VALUES ('s1','u','d','cur','prev',5,99), ('s2','u','d','only',NULL,6,99)",
    )
    command.upgrade(cfg, "0002")
    assert rows(blank_db, "SELECT token_hash, session_id FROM session_tokens") == {
        ("cur", "s1"),
        ("prev", "s1"),
        ("only", "s2"),
    }
    engine = create_engine(blank_db, poolclass=NullPool)
    assert "previous_token_hash" not in {c["name"] for c in inspect(engine).get_columns("sessions")}
    engine.dispose()
    command.downgrade(cfg, "0001")
    assert rows(blank_db, "SELECT previous_token_hash FROM sessions WHERE id='s1'") == {("prev",)}
    assert "session_tokens" not in table_names(blank_db)
    command.upgrade(cfg, "head")  # and forward again, through every later revision
    assert "session_tokens" in table_names(blank_db)


def test_0003_adds_and_drops_the_audit_table(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "0002")
    assert "audit_events" not in table_names(blank_db)
    command.upgrade(cfg, "0003")
    execute(blank_db, "INSERT INTO audit_events (at, action, details) VALUES (1, 'x', '{}')")
    command.downgrade(cfg, "0002")
    assert "audit_events" not in table_names(blank_db)


def test_0004_0005_keep_existing_rows(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "0003")
    execute(
        blank_db,
        "INSERT INTO users (id, username, display_name, password_hash, is_admin, created_at) "
        "VALUES ('u','u','U','x',false,1)",
        "INSERT INTO conversations (id, type, created_by, created_at, updated_at) VALUES ('c','group','u',1,1)",
    )
    command.upgrade(cfg, "head")
    assert rows(blank_db, "SELECT id, disappearing_seconds FROM conversations") == {("c", None)}


def test_0006_backfills_invitation_uses_and_keeps_existing_data(blank_db):
    """Redeemable now means uses < max_uses: an invitation redeemed before the upgrade must not become usable again."""
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "0005")
    execute(
        blank_db,
        "INSERT INTO users (id, username, display_name, password_hash, is_admin, created_at) "
        "VALUES ('u','u','U','x',false,1)",
        "INSERT INTO invitations (id, code_hash, is_admin, created_by, created_at, expires_at, used_at, used_by) "
        "VALUES ('used','h1',false,NULL,1,9999999999999,5,'u'), ('fresh','h2',false,NULL,1,9999999999999,NULL,NULL)",
    )
    command.upgrade(cfg, "0006")
    assert rows(blank_db, "SELECT id, max_uses, uses FROM invitations") == {("used", 1, 1), ("fresh", 1, 0)}
    assert rows(blank_db, "SELECT suspended_at, share_presence, presence_last_seen_at FROM users") == {
        (None, False, None)
    }
    assert {"login_failures", "upload_sessions"} <= table_names(blank_db)
    command.downgrade(cfg, "0005")  # with data in the tables, on SQLite and PostgreSQL
    assert not {"login_failures", "upload_sessions"} & table_names(blank_db)
    engine = create_engine(blank_db, poolclass=NullPool)
    cols = {c["name"] for c in inspect(engine).get_columns("invitations")} | {
        c["name"] for c in inspect(engine).get_columns("users")
    }
    engine.dispose()
    assert not cols & {"max_uses", "uses", "suspended_at", "share_presence", "presence_last_seen_at"}
    assert rows(blank_db, "SELECT id FROM invitations") == {("used",), ("fresh",)}
    command.upgrade(cfg, "head")


def test_0007_adds_push_health_and_server_state_and_keeps_devices(blank_db):
    cfg = alembic_config(blank_db)
    command.upgrade(cfg, "0006")
    execute(
        blank_db,
        "INSERT INTO users (id, username, display_name, password_hash, is_admin, created_at) "
        "VALUES ('u','u','U','x',false,1)",
        "INSERT INTO devices (id, user_id, number, name, created_at, last_seen_at, acked_cursor) "
        "VALUES ('d','u',1,'Phone',1,1,0)",
    )
    command.upgrade(cfg, "0007")
    assert rows(blank_db, "SELECT last_push_at, last_push_status, push_failures FROM devices") == {(None, None, 0)}
    assert "server_state" in table_names(blank_db)
    command.downgrade(cfg, "0006")  # with a device in the table, on SQLite and PostgreSQL
    assert "server_state" not in table_names(blank_db)
    engine = create_engine(blank_db, poolclass=NullPool)
    cols = {c["name"] for c in inspect(engine).get_columns("devices")}
    engine.dispose()
    assert not cols & {"last_push_at", "last_push_status", "push_failures"}
    assert rows(blank_db, "SELECT id FROM devices") == {("d",)}
    command.upgrade(cfg, "head")

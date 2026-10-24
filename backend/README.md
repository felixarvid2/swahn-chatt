# FamilyChat backend

FastAPI + SQLAlchemy + SQLite service that stores and forwards end-to-end encrypted messages (it never
decrypts). Contract: [`../docs/API.md`](../docs/API.md). Deployment: [`../docs/RASPBERRY_PI_SETUP.md`](../docs/RASPBERRY_PI_SETUP.md).

## Develop

```bash
/usr/bin/python3.12 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest              # full suite, isolated temp databases (SQLite)
# the same suite against PostgreSQL (needs a role that may CREATE DATABASE; tests needing the SQLite file skip):
docker run -d --name fcpg -e POSTGRES_USER=fc -e POSTGRES_PASSWORD=pw -p 5433:5432 postgres:16
FAMILYCHAT_TEST_DATABASE_URL=postgresql+psycopg://fc:pw@localhost:5433/postgres .venv/bin/python -m pytest
.venv/bin/ruff check . && .venv/bin/ruff format --check .
scripts/dev-server.sh                   # ENV=development, data in ./data-dev, http://0.0.0.0:8000, migrations first
```

Docs of the running dev server: <http://localhost:8000/api/v1/docs>.

Admin CLI (run from `backend/`, same `FAMILYCHAT_*` environment as the server):

```bash
FAMILYCHAT_ENV=development python -m app.cli create-invite --admin   # last stdout line = the code
python -m app.cli list-users | remove-user <name> | reset-password <name> | audit [--limit N]
python -m app.cli backup <dir> | verify-backup <archive> | restore <archive> [--force] | check-config
FAMILYCHAT_ENV=development python -m app.cli seed-dev [--password P]  # alice, bob, carol + chats
```

Schema changes only through Alembic: edit `app/models.py`, then `alembic revision --autogenerate -m "..."`
(the test suite fails if models and migrations disagree).

## Layout

`app/main.py` app factory and lifespan; `config.py` settings; `db.py` engine/session (SQLite WAL; write sessions `BEGIN IMMEDIATE`, read-only work uses
`make_read_session_factory` / `get_read_db` with a deferred `BEGIN`, and Argon2 never runs inside a transaction);
`models.py`; `schemas.py`; `security.py`; `ratelimit.py`; `hub.py` WebSocket registry; `routers/*` HTTP/WS routes;
`services/*` business logic (events fan-out, conversations, messages, sync/ack, keys, attachments, push, backup);
`cli.py`; `migrations/`; `tests/`. One uvicorn worker only: hub, rate limiter and push queue are in-process.

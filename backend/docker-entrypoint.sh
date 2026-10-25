#!/bin/sh
# Apply database migrations, then run ONE uvicorn worker (the WebSocket hub, rate limiter and push
# queue live in this process; several workers would need a broker).
set -eu
umask 077
# Before the rename the database file was familychat.db: adopt it before alembic would create an empty new one.
python -m app.legacy
alembic upgrade head
printf '{"level": "INFO", "logger": "entrypoint", "msg": "migrations applied: %s"}\n' "$(alembic current 2>/dev/null | tail -n1)"
exec uvicorn app.main:create_app --factory \
  --host 0.0.0.0 --port 8000 --workers 1 \
  --no-access-log --no-proxy-headers --no-server-header \
  --log-config /app/uvicorn_log.json \
  --ws-ping-interval 25 --ws-ping-timeout 60 --ws-max-size 65536  # WebSocket frames are tiny control messages

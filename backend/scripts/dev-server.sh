#!/usr/bin/env bash
# Start the Swahn Chat backend for local development: ENV=development, data in backend/data-dev,
# port 8000 on all interfaces, migrations applied first. Usage: backend/scripts/dev-server.sh
# Override with SWAHNCHAT_DATA_DIR / PORT / HOST if needed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -x .venv/bin/python ]; then
  PY=.venv/bin/python
else
  PY=${PYTHON:-python3.12}
fi

export SWAHNCHAT_ENV=development
export SWAHNCHAT_DATA_DIR="${SWAHNCHAT_DATA_DIR:-$PWD/data-dev}"
export SWAHNCHAT_PUSH_ALLOW_HTTP="${SWAHNCHAT_PUSH_ALLOW_HTTP:-true}"
export SWAHNCHAT_LOG_LEVEL="${SWAHNCHAT_LOG_LEVEL:-INFO}"
mkdir -p "$SWAHNCHAT_DATA_DIR"

"$PY" -m alembic upgrade head
exec "$PY" -m uvicorn app.main:create_app --factory \
  --host "${HOST:-0.0.0.0}" --port "${PORT:-8000}" --workers 1 \
  --no-access-log --log-config uvicorn_log.json --ws-ping-interval 25 --ws-ping-timeout 60

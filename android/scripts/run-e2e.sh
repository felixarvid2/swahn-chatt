#!/usr/bin/env bash
# Runs the two-user end-to-end test (core/client TwoUserEndToEndTest) against a real backend started from
# ../backend with a throw-away data directory.
#
#   android/scripts/run-e2e.sh            # uses ../backend/.venv
#   KEEP=1 android/scripts/run-e2e.sh     # keep the data dir and log for inspection
#
# What it does: migrates a temp SQLite database, starts ONE uvicorn worker on a free port in development mode, creates
# an admin invitation with the CLI, exports FAMILYCHAT_TEST_URL / _ADMIN_INVITE / _DB and runs the Gradle tests:
# core:client TwoUserEndToEndTest (in-memory stores) and app RoomEngineE2ETest (one phone on the app's Room stores).
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$(cd "$ANDROID_DIR/../backend" && pwd)"

if [ -x "$BACKEND_DIR/.venv/bin/python" ]; then
  PY="$BACKEND_DIR/.venv/bin/python"
else
  echo "backend virtualenv not found at $BACKEND_DIR/.venv (create it: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt)" >&2
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/familychat-e2e.XXXXXX")"
SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [ -z "${KEEP:-}" ]; then rm -rf "$WORK"; else echo "kept $WORK" >&2; fi
}
trap cleanup EXIT

export FAMILYCHAT_ENV=development
export FAMILYCHAT_DATA_DIR="$WORK/data"
export FAMILYCHAT_LOG_LEVEL="${FAMILYCHAT_LOG_LEVEL:-WARNING}"
# Cheaper password hashing keeps the test fast; everything else stays at production behaviour (rate limits on).
export FAMILYCHAT_ARGON2_TIME_COST="${FAMILYCHAT_ARGON2_TIME_COST:-1}"
export FAMILYCHAT_ARGON2_MEMORY_KB="${FAMILYCHAT_ARGON2_MEMORY_KB:-8192}"
export FAMILYCHAT_ARGON2_PARALLELISM="${FAMILYCHAT_ARGON2_PARALLELISM:-1}"
mkdir -p "$FAMILYCHAT_DATA_DIR"

cd "$BACKEND_DIR"
"$PY" -m alembic upgrade head >"$WORK/alembic.log" 2>&1 || { cat "$WORK/alembic.log" >&2; exit 1; }

PORT="$("$PY" - <<'PYEOF'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PYEOF
)"

"$PY" -m uvicorn app.main:create_app --factory --host 127.0.0.1 --port "$PORT" --workers 1 \
  --no-access-log --ws-ping-interval 25 --ws-ping-timeout 60 >"$WORK/server.log" 2>&1 &
SERVER_PID=$!

URL="http://127.0.0.1:$PORT"
for _ in $(seq 1 100); do
  if curl -fsS "$URL/api/v1/healthz" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo "backend exited:" >&2; cat "$WORK/server.log" >&2; exit 1; fi
  sleep 0.2
done
curl -fsS "$URL/api/v1/healthz" >/dev/null || { echo "backend did not become healthy" >&2; cat "$WORK/server.log" >&2; exit 1; }

# The code is the LAST stdout line of the CLI.
INVITE="$("$PY" -m app.cli create-invite --admin --note e2e 2>/dev/null | tail -n 1)"
[ -n "$INVITE" ] || { echo "could not create an admin invitation" >&2; exit 1; }
# a second one: invitations work once and each test class creates its own administrator
APP_INVITE="$("$PY" -m app.cli create-invite --admin --note e2e-app 2>/dev/null | tail -n 1)"
[ -n "$APP_INVITE" ] || { echo "could not create the second admin invitation" >&2; exit 1; }

export FAMILYCHAT_TEST_URL="$URL"
export FAMILYCHAT_TEST_ADMIN_INVITE="$INVITE"
export FAMILYCHAT_TEST_APP_ADMIN_INVITE="$APP_INVITE"
export FAMILYCHAT_TEST_DB="$FAMILYCHAT_DATA_DIR/familychat.db"

cd "$ANDROID_DIR"
set +e
./gradlew :core:client:test --tests '*TwoUserEndToEndTest*' :app:testDebugUnitTest --tests '*RoomEngineE2ETest*' --rerun-tasks -i "$@"
STATUS=$?
set -e
if [ "$STATUS" -ne 0 ]; then
  echo "---- backend log (tail) ----" >&2
  tail -n 40 "$WORK/server.log" >&2 || true
fi
exit "$STATUS"

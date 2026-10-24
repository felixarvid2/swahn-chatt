#!/usr/bin/env bash
# Restore a backup archive into the running deployment. Stops the backend, replaces the data
# (the previous data is kept in the volume as /data/pre-restore-<time>), starts the backend again.
#   ./restore.sh path/to/familychat-backup-YYYYMMDD-HHMMSS.tar.gz [-y]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
archive="${1:?usage: restore.sh <archive.tar.gz> [-y]}"
[ -f "$archive" ] || { echo "no such file: $archive" >&2; exit 1; }
tar -tzf "$archive" | grep -qx 'manifest.json' || { echo "not a FamilyChat backup: $archive" >&2; exit 1; }

if [ "${2:-}" != "-y" ]; then
  read -r -p "Replace ALL current FamilyChat data with $archive? [y/N] " answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "aborted"; exit 1; }
fi

docker compose stop backend
# The archive is streamed through stdin so file permissions on the host do not matter.
docker compose run --rm --no-deps -T --entrypoint sh backend -c \
  'cat > /tmp/restore.tar.gz && python -m app.cli restore /tmp/restore.tar.gz --force' < "$archive"
docker compose up -d backend
echo "restored; the backend applies any newer migrations on start"

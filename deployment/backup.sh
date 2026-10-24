#!/usr/bin/env bash
# Nightly-safe backup of a running FamilyChat: asks the container for a consistent snapshot
# (SQLite online backup + attachments + checksum manifest), copies it to the host, prunes old ones and
# optionally copies the archives off the machine with rclone.
#   ./backup.sh [DEST_DIR]            DEST_DIR defaults to ./backups
#   ./backup.sh --prune [DEST_DIR]    only apply the retention rule (no new backup, no rclone)
# Settings (environment first, then deployment/.env):
#   FAMILYCHAT_BACKUP_KEEP=14                  keep the newest N archives (N >= 1); the old KEEP=N still works
#   FAMILYCHAT_BACKUP_RCLONE_REMOTE=remote:dir `rclone copy` the backup directory there after a successful backup
# The archive holds ciphertext and metadata only (see docs/SECURITY.md) but also password hashes: keep it private,
# and prefer an rclone "crypt" remote so that the copy off the machine is encrypted too.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# Value of NAME from the environment, else from .env (read literally, never sourced), else the default.
setting() {
  local name="$1" default="$2" value=""
  if [ -n "${!name:-}" ]; then
    printf '%s' "${!name}"
    return
  fi
  if [ -f .env ]; then
    value="$(grep -E "^${name}=" .env | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
  fi
  printf '%s' "${value:-$default}"
}

prune_only=0
if [ "${1:-}" = "--prune" ]; then
  prune_only=1
  shift
fi
DEST="${1:-./backups}"
KEEP="$(setting FAMILYCHAT_BACKUP_KEEP "${KEEP:-14}")"
RCLONE_REMOTE="$(setting FAMILYCHAT_BACKUP_RCLONE_REMOTE "")"
case "$KEEP" in
  '' | *[!0-9]* | 0) echo "FAMILYCHAT_BACKUP_KEEP must be a whole number >= 1 (got '$KEEP')" >&2; exit 2 ;;
esac

# Retention: keep the newest $KEEP archives. The timestamp in the name sorts chronologically.
prune() {
  local dir="$1" keep="$2" i
  local -a files
  shopt -s nullglob
  files=("$dir"/familychat-backup-*.tar.gz) # glob results are sorted by name, oldest first
  shopt -u nullglob
  for ((i = 0; i < ${#files[@]} - keep; i++)); do
    rm -f -- "${files[i]}"
    echo "pruned $(basename "${files[i]}")" >&2
  done
}

if [ "$prune_only" = 1 ]; then
  [ -d "$DEST" ] || { echo "no such directory: $DEST" >&2; exit 1; }
  prune "$DEST" "$KEEP"
  exit 0
fi

mkdir -p "$DEST"
chmod 700 "$DEST"

# Staged on the data volume (the container's root filesystem is read-only and /tmp is a small tmpfs).
remote="$(docker compose exec -T backend python -m app.cli backup /data/.backup-tmp | tail -n 1 | tr -d '\r')"
name="$(basename "$remote")"
docker compose cp "backend:$remote" "$DEST/$name"
docker compose exec -T backend rm -f "$remote"

# Cheap sanity check before pruning anything: the archive must be a readable gzip tarball with a manifest.
tar -tzf "$DEST/$name" | grep -qx 'manifest.json' || { echo "backup $name is damaged" >&2; exit 1; }
chmod 600 "$DEST/$name"

prune "$DEST" "$KEEP"

# Off-site copy. The local backup is already safe at this point; a failed upload makes the run fail (so the
# systemd unit shows red) but never removes anything. Copying the whole directory also catches up after an outage.
status=0
if [ -n "$RCLONE_REMOTE" ]; then
  RCLONE_BIN="${RCLONE:-rclone}"
  if command -v "$RCLONE_BIN" >/dev/null 2>&1; then
    "$RCLONE_BIN" copy "$DEST" "$RCLONE_REMOTE" --include 'familychat-backup-*.tar.gz' >&2 ||
      { echo "rclone copy to $RCLONE_REMOTE failed; the local backup $name is intact" >&2; status=3; }
  else
    echo "FAMILYCHAT_BACKUP_RCLONE_REMOTE is set but rclone is not installed; the local backup $name is intact" >&2
    status=3
  fi
fi

echo "$DEST/$name"
exit "$status"

#!/usr/bin/env bash
# Nightly-safe backup of a running FamilyChat: asks the container for a consistent snapshot
# (SQLite online backup + attachments + checksum manifest), copies it to the host and prunes old ones.
#   ./backup.sh [DEST_DIR]            DEST_DIR defaults to ./backups   KEEP=14 keeps the 14 newest
# The archive holds ciphertext and metadata only (see docs/SECURITY.md) but also password hashes: keep it private.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
DEST="${1:-./backups}"
KEEP="${KEEP:-14}"

mkdir -p "$DEST"
chmod 700 "$DEST"

remote="$(docker compose exec -T backend python -m app.cli backup /tmp/backups | tail -n 1 | tr -d '\r')"
name="$(basename "$remote")"
docker compose cp "backend:$remote" "$DEST/$name"
docker compose exec -T backend rm -f "$remote"

# Cheap sanity check before pruning anything: the archive must be a readable gzip tarball with a manifest.
tar -tzf "$DEST/$name" | grep -qx 'manifest.json' || { echo "backup $name is damaged" >&2; exit 1; }
chmod 600 "$DEST/$name"

# Retention: keep the newest $KEEP archives.
ls -1t "$DEST"/familychat-backup-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f --

echo "$DEST/$name"

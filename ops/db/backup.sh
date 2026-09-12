#!/bin/sh
set -eu

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"

BACKUP_DIR="${TJ_BACKUP_DIR:-/backups}"
RETENTION_DAYS="${TJ_BACKUP_RETENTION_DAYS:-14}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" || true

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FINAL="$BACKUP_DIR/tiempojusto-${STAMP}.dump"
TMP="$FINAL.tmp"

cleanup() {
    rm -f "$TMP"
}
trap cleanup EXIT INT TERM

echo "Starting TiempoJusto PostgreSQL backup at $STAMP"
pg_dump \
    --format=custom \
    --compress=9 \
    --no-owner \
    --no-acl \
    --file "$TMP" \
    "$PGDATABASE"

chmod 600 "$TMP"
mv "$TMP" "$FINAL"
sha256sum "$FINAL" > "$FINAL.sha256"
chmod 600 "$FINAL.sha256"
trap - EXIT INT TERM

# Delete only TiempoJusto backup artifacts older than the configured retention.
find "$BACKUP_DIR" -type f \
    \( -name 'tiempojusto-*.dump' -o -name 'tiempojusto-*.dump.sha256' \) \
    -mtime "+$RETENTION_DAYS" -delete

echo "Backup completed: $(basename "$FINAL")"

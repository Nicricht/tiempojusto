#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/tiempojusto-backup.dump" >&2
    exit 2
fi

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${TJ_RESTORE_DATABASE:?TJ_RESTORE_DATABASE is required}"
: "${TJ_RESTORE_CONFIRM:?TJ_RESTORE_CONFIRM=YES is required}"

if [ "$TJ_RESTORE_CONFIRM" != "YES" ]; then
    echo "Refusing restore without TJ_RESTORE_CONFIRM=YES" >&2
    exit 3
fi
if [ "$TJ_RESTORE_DATABASE" = "$PGDATABASE" ]; then
    echo "Refusing to restore over the active database. Restore into a new database first." >&2
    exit 4
fi

BACKUP="$1"
if [ ! -f "$BACKUP" ]; then
    echo "Backup file does not exist: $BACKUP" >&2
    exit 5
fi

if [ -f "$BACKUP.sha256" ]; then
    (cd "$(dirname "$BACKUP")" && sha256sum -c "$(basename "$BACKUP").sha256")
else
    echo "WARNING: checksum sidecar not found; continuing only because explicit confirmation was supplied." >&2
fi

EXISTS="$(psql -d postgres -At -v ON_ERROR_STOP=1 -v db="$TJ_RESTORE_DATABASE" <<'SQL'
SELECT 1 FROM pg_database WHERE datname = :'db';
SQL
)"
if [ -n "$EXISTS" ]; then
    echo "Refusing to overwrite existing database $TJ_RESTORE_DATABASE" >&2
    exit 6
fi

createdb "$TJ_RESTORE_DATABASE"
trap 'echo "Restore failed. Drop the incomplete target database manually after inspection: $TJ_RESTORE_DATABASE" >&2' EXIT INT TERM

pg_restore \
    --exit-on-error \
    --no-owner \
    --no-acl \
    --dbname "$TJ_RESTORE_DATABASE" \
    "$BACKUP"

trap - EXIT INT TERM
echo "Restore completed into new database: $TJ_RESTORE_DATABASE"

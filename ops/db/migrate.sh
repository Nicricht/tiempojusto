#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"

psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS public.tj_schema_migration (
    version text PRIMARY KEY,
    checksum_sha256 char(64) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now()
);
SQL

checksum_file() {
    sha256sum "$1" | awk '{print $1}'
}

checksum_base_schema() {
    {
        cat database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql
        for part in database/schema/parts/part_00.sql \
                    database/schema/parts/part_01.sql \
                    database/schema/parts/part_02.sql \
                    database/schema/parts/part_03.sql \
                    database/schema/parts/part_04.sql \
                    database/schema/parts/part_05.sql \
                    database/schema/parts/part_06.sql \
                    database/schema/parts/part_07.sql \
                    database/schema/parts/part_08.sql; do
            cat "$part"
        done
    } | sha256sum | awk '{print $1}'
}

applied_checksum() {
    version="$1"
    psql -At -v ON_ERROR_STOP=1 -v version="$version" \
        -c "SELECT checksum_sha256 FROM public.tj_schema_migration WHERE version = :'version'" 2>/dev/null || true
}

record_version() {
    version="$1"
    checksum="$2"
    psql -v ON_ERROR_STOP=1 -v version="$version" -v checksum="$checksum" \
        -c "INSERT INTO public.tj_schema_migration(version, checksum_sha256) VALUES (:'version', :'checksum')"
}

apply_file() {
    version="$1"
    file="$2"
    checksum="$3"
    prior="$(applied_checksum "$version")"

    if [ -n "$prior" ]; then
        if [ "$prior" != "$checksum" ]; then
            echo "ERROR: checksum drift for already-applied migration $version" >&2
            exit 42
        fi
        echo "Migration $version already applied; checksum verified."
        return
    fi

    echo "Applying $version from $file"
    psql -v ON_ERROR_STOP=1 -f "$file"
    record_version "$version" "$checksum"
}

BASE="database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql"
apply_file "V1_0" "$BASE" "$(checksum_base_schema)"

for entry in \
    "V1_1|database/migrations/V1_1__geo_routing_eta.sql" \
    "V1_2|database/migrations/V1_2__webrtc_media.sql" \
    "V1_3|database/migrations/V1_3__oauth2_identity.sql" \
    "V1_4|database/migrations/V1_4__online_reconnect_persistence.sql" \
    "V1_5|database/migrations/V1_5__persistent_session_settlement.sql" \
    "V1_6|database/migrations/V1_6__payment_provider_bindings.sql" \
    "V1_7|database/migrations/V1_7__identity_provider_bindings.sql" \
    "V1_8|database/migrations/V1_8__bid_reservation_replacement.sql" \
    "V1_9|database/migrations/V1_9__payment_webhook_reconciliation.sql" \
    "V1_10|database/migrations/V1_10__kyc_webhook_reconciliation.sql"
do
    version="${entry%%|*}"
    file="${entry#*|}"
    apply_file "$version" "$file" "$(checksum_file "$file")"
done

echo "TiempoJusto database migrations are current."

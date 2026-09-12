# TiempoJusto DB Operations

- `migrate.sh`: applies V1.0 then V1.1...V1.10 in fixed order and records SHA-256 in `public.tj_schema_migration`.
- `backup.sh`: creates a PostgreSQL custom-format backup plus SHA-256 sidecar and retention cleanup.
- `restore.sh`: restores only into a new database and refuses overwrite of the active database.

All scripts consume PostgreSQL credentials from environment/secret injection. Never commit credentials or generated backup artifacts.

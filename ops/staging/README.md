# TiempoJusto Staging

This directory contains the reproducible staging topology for issue #27.

Run only after loading required values from an external secret store:

```bash
docker compose -f ops/staging/docker-compose.staging.yml up -d --build
```

Public edge: Caddy HTTPS on 80/443. Internal only: backend direct ports, PostgreSQL/PostGIS, Prometheus, Alertmanager, migration and backup services.

Operational procedures, required variables, restore and rollback are documented in `docs/ops/STAGING_RUNBOOK_V1.md`. Evidence gates are in `docs/ops/STAGING_CHECKLIST_V1.md`.

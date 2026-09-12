# Staging Implementation Status V1

Issue: #27.

## Implemented in repository

- reproducible Docker staging topology for frontend, backend, PostgreSQL/PostGIS, HTTPS gateway, Prometheus, Alertmanager and backup worker;
- backend and frontend production containers;
- checksum-guarded ordered database migrator through V1.10;
- readiness/liveness and Prometheus endpoint configuration;
- JSON structured logging with request correlation id;
- bounded low-cardinality metrics for Auth, Auction, Session, KYC/Payment webhooks and finance surfaces;
- settlement and payout release worker counters;
- alert rules for availability, 5xx, Auth/Auction/Session failures, webhook failures, settlement/payout retries and rate-limit bursts;
- documented technical rate limits enforced by backend when enabled;
- stateless JWT/OIDC hardening, exact-origin CORS and hardened internal-route boundary;
- secret/local backup/key patterns excluded from repository artifacts;
- dependency and secret scanning workflow;
- automated backup and safe restore-to-new-database scripts;
- CI proof for migrations, idempotent rerun, backup, checksum and restore;
- external HTTPS smoke workflow;
- incident/rollback runbook and evidence checklist.

## Deliberately not claimed as complete

The repository cannot manufacture external provider/account evidence. #27 must remain open until a real staging deployment proves DNS/HTTPS, real OAuth/OIDC JWKS, real KYC and payment sandbox credentials/webhooks, real WebRTC/TURN, external alert delivery, off-host backup retention/restore, and a full provider-backed Golden Path.

No secret values belong in this document or any commit.

# Staging Implementation Status V1

Issue: #27.

## Implemented in repository

- reproducible Docker staging topology for frontend, backend, PostgreSQL/PostGIS, HTTPS gateway, coturn, Prometheus, Alertmanager and backup worker;
- backend and frontend production containers;
- checksum-guarded ordered database migrator through V1.11;
- readiness/liveness and Prometheus endpoint configuration;
- JSON structured logging with request correlation id;
- bounded low-cardinality metrics for Auth, Auction, Session, KYC/Payment webhooks and finance surfaces;
- settlement and payout release worker counters;
- alert rules for availability, 5xx, Auth/Auction/Session failures, webhook failures, settlement/payout retries and rate-limit bursts;
- documented technical rate limits enforced by backend when enabled;
- stateless JWT/OIDC hardening, exact-origin CORS and hardened internal-route boundary;
- real WebRTC signaling seam plus coturn REST credentials with short TTL, no persistent private recording and browser relay CI;
- frontend ONLINE Golden Path with explicit loading/error/empty/timeout/offline states and recovery after reconnect;
- TURN credential refresh before expiry without restarting Session or billing authority;
- secret/local backup/key patterns excluded from repository artifacts;
- dependency and secret scanning workflow;
- automated backup and safe restore-to-new-database scripts;
- CI proof for migrations, idempotent rerun, backup, checksum and restore, including V1.11 ephemeral signaling exclusion;
- external HTTPS smoke workflow;
- incident/rollback runbook and evidence checklist.

## Deliberately not claimed as complete

The repository cannot manufacture external provider/account evidence. #27 must remain open until a real staging deployment proves DNS/HTTPS, real OAuth/OIDC JWKS, real KYC and payment sandbox credentials/webhooks, public WebRTC/TURN, external alert delivery, off-host backup retention/restore, and a full provider-backed Golden Path.

The repository-side frontend and TURN hardening merged in `8ddc1d3e6b1699d77bbcc7111abd94c70f419e84` reduces the remaining work to deployment/provider evidence; it does not substitute for that evidence.

No secret values belong in this document or any commit.

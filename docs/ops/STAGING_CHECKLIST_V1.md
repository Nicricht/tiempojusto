# TiempoJusto Staging Gate Checklist V1

Use this checklist as evidence for issue #27. A checkbox means proven by execution, not merely configured.

## Reproducible stack

- [ ] Public DNS resolves to the staging host.
- [ ] `https://<staging>/healthz` returns readiness UP.
- [ ] Frontend loads over HTTPS.
- [ ] Backend API is reachable only through HTTPS edge routes.
- [ ] PostgreSQL/PostGIS is private and not Internet-exposed.
- [ ] `migrate` completes before backend startup.
- [ ] Re-running `migrate` reports every applied checksum unchanged.

## Security

- [ ] `TJ_AUTH_DEV_HEADER_ENABLED=false`.
- [ ] JWT is validated against real sandbox issuer + audience + JWKS.
- [ ] Anonymous protected API request returns 401/403.
- [ ] CORS allows only the approved staging origin.
- [ ] `/internal/**` is not public.
- [ ] `/actuator/prometheus` is not public.
- [ ] Rate limits return 429 + `Retry-After` when exceeded.
- [ ] KYC webhook rejects an invalid signature.
- [ ] Payment webhook rejects an invalid signature.
- [ ] Dependency review/security scan is green.
- [ ] Secret scan is green.

## Observability

- [ ] JSON logs contain `requestId`.
- [ ] Caller-safe `X-Request-Id` is echoed by API.
- [ ] Prometheus scrapes backend management endpoint.
- [ ] Metrics exist for auth, Auction, Session, KYC webhook, Payment webhook, settlement and payout.
- [ ] Alert rules load without errors.
- [ ] A real alert reaches the approved external notification channel.

## Backup and restore

- [ ] Automated staging backup exists with SHA-256 sidecar.
- [ ] Backup storage is separated from the running DB volume/host failure domain.
- [ ] Restore is performed into a new database.
- [ ] Restored DB contains migration history and PostGIS.
- [ ] Restored staging data passes the required smoke checks.

## Provider-backed Golden Path

- [ ] Real sandbox OAuth/OIDC login/JWT.
- [ ] Real sandbox KYC VERIFIED path.
- [ ] Real sandbox KYC REJECTED path.
- [ ] Real payment sandbox reserve/authorization.
- [ ] Real payment sandbox capture.
- [ ] Real payment sandbox cancellation/release.
- [ ] Real payment sandbox refund.
- [ ] Real provider webhook delivery + signature verification + dedup/retry.
- [ ] Real WebRTC/TURN ONLINE room.
- [ ] FREE_ONLINE 2 minutes and bilateral paid consent remain server-authoritative.
- [ ] Disconnect/reconnect billing pause and bilateral resume are proven.
- [ ] Session settlement posts only when exact CLP result is representable; otherwise `PENDING_ROUNDING_POLICY`.
- [ ] Ledger remains balanced.
- [ ] Payout cannot become AVAILABLE before the minimum 60-minute hold.

Do not close #27 until all external checkboxes required for the pilot are backed by concrete run/deployment evidence.

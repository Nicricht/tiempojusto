# Deployment Invariants

A staging deployment is acceptable only when:

- the public edge is HTTPS-only;
- backend, DB, management metrics and backup services are not directly Internet-exposed;
- `TJ_AUTH_DEV_HEADER_ENABLED=false` and real JWT validation is enabled;
- migrations complete before backend startup;
- secrets are injected outside Git;
- signed provider webhooks remain the only public unauthenticated provider callbacks;
- no deployment change invents a CLP rounding policy or bypasses ledger/session invariants.

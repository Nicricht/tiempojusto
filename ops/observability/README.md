# TiempoJusto Observability

Prometheus scrapes `backend:8081/actuator/prometheus` only on the private staging network. The public Caddy edge does not route `/actuator/**`.

`alerts.yml` defines the minimum alert rules required for #27. `alertmanager.yml` intentionally contains no external notification credential or URL. The deployment must inject an approved secret-backed receiver before alert delivery can be marked as proven.

Metrics are intentionally low-cardinality. Do not add user IDs, Auction IDs, Session IDs, provider references, tokens, KYC payloads, biometric data or audiovisual content as labels.

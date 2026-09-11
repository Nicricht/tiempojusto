# TiempoJusto

Repositorio técnico del proyecto **TiempoJusto**.

## Estado actual

TiempoJusto ya pasó de definición funcional a ingeniería ejecutable. El repositorio contiene schema PostgreSQL/PostGIS V1, máquinas de estado Java 21, Ledger financiero + PaymentPort Mock, OpenAPI REST V1, WebSocket V1, Geo/ETA V1, WebRTC/Media V1, wireframes UX P2.1, pruebas de contrato y workflows de CI.

## Estructura principal

- `database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql`
  Loader ejecutable del schema PostgreSQL/PostGIS V1.0.
- `database/migrations/V1_1__geo_routing_eta.sql`
  Migración P1.3 para privacidad geográfica, ETA y elegibilidad presencial.
- `database/migrations/V1_2__webrtc_media.sql`
  Migración P1.4 para estado de media, cámara válida, no grabación, incidents y reconexión.
- `backend/state-machines/`
  Máquinas de estado Java 21 y 34 contract tests.
- `backend/finance/`
  Ledger de doble entrada, PaymentPort agnóstico, MockPaymentPort y 22 contract tests.
- `backend/geo/`
  Geo Core Java 21, RoutingPort, MockRoutingPort y 12 contract tests.
- `backend/media/`
  Media Core Java 21, WebRtcPort, MockWebRtcPort, TURN, camera validity, reconnect y 17 contract tests.
- `api/openapi/openapi.yaml`
  Contrato REST OpenAPI 3.1.
- `api/realtime/protocol.json`
  Protocolo WebSocket V1.
- `docs/ux/P2_1_WIREFRAMES_V1.md`
  Trazabilidad de los wireframes P2.1 y enlace al archivo Figma editable.

## Wireframes UX P2.1

Figma editable:
https://www.figma.com/design/xnWgItVIbdxLPmy9i6oJNk

Incluye onboarding/KYC, discovery, mapa aproximado, perfil, Proposal, Disponible Ahora, Meta Ahora, Auction, Live, ganador, presencial, Online, sesión pagada/reconexión, extensión, Wallet/Payout, Safety y Rating.

## Estado de hitos

| Hito | Estado |
|---|---|
| Producto / reglas V1.7 | ✅ definido |
| ER físico V1.0 | ✅ diseñado |
| PostgreSQL/PostGIS V1.0 | ✅ versionado |
| State Machines Java 21 | ✅ 34/34 tests |
| Ledger + PaymentPort Mock | ✅ 22/22 tests |
| OpenAPI REST V1 | ✅ implementado |
| WebSocket V1 | ✅ implementado |
| Geo / routing / ETA V1 | ✅ 12/12 tests |
| WebRTC / Media V1 | ✅ 17/17 tests |
| UX / wireframes P2.1 | ✅ Figma V1 |
| GitHub Actions | ✅ configurado |
| Admin / HumanReviewQueue | ⏭️ siguiente |
| Integración backend completa | pendiente |
| PostgreSQL real / migrations | pendiente |
| Integración de proveedores reales | pendiente |
| E2E / security / observability | pendiente |
| Staging / piloto | pendiente |

## Ejecutar módulos

```bash
cd backend/state-machines && bash run-tests.sh
cd backend/finance && bash run-tests.sh
cd backend/geo && bash run-tests.sh
cd backend/media && bash run-tests.sh
```

## Ejecutar schema PostgreSQL

Desde `database/schema/` con `psql`:

```bash
psql -d tiempojusto -f TiempoJusto_PostgreSQL_Schema_V1_0.sql
psql -d tiempojusto -f ../migrations/V1_1__geo_routing_eta.sql
psql -d tiempojusto -f ../migrations/V1_2__webrtc_media.sql
```

Las migraciones aún requieren ejecución real contra PostgreSQL 16 + PostGIS antes de considerarse físicamente validadas.

## Próximo hito

**P2.2 Admin / HumanReviewQueue**, para materializar moderación, evidencia, apelaciones, Safety S0-S5, riesgo y auditoría financiera de solo lectura.

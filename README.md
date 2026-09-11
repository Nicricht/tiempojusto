# TiempoJusto

Repositorio técnico del proyecto **TiempoJusto**.

## Estado actual

TiempoJusto ya pasó de definición funcional a ingeniería ejecutable. El repositorio contiene el schema PostgreSQL/PostGIS V1, máquinas de estado Java 21, Ledger financiero + PaymentPort Mock, OpenAPI REST V1, WebSocket V1, Geo/ETA V1, WebRTC/Media V1, pruebas de contrato y workflows de CI.

## Estructura principal

- `database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql`  
  Loader ejecutable del schema PostgreSQL/PostGIS V1.0.
- `database/schema/parts/`  
  9 fragmentos que, en orden, reconstruyen el artefacto SQL original.
- `database/migrations/V1_1__geo_routing_eta.sql`  
  Migración P1.3 para privacidad geográfica, ETA y elegibilidad presencial.
- `database/migrations/V1_2__webrtc_media.sql`  
  Migración P1.4 para estado de media, cámara válida, no grabación, incidents y reconexión.
- `backend/state-machines/src/main/java/`  
  Máquinas de estado Java 21.
- `backend/state-machines/src/test/java/`  
  Suite de contract tests de máquinas de estado.
- `backend/finance/src/main/java/`  
  Ledger de doble entrada, PaymentPort agnóstico, MockPaymentPort y FinanceEngine.
- `backend/finance/src/test/java/`  
  Contract tests de reservas, capture, refund, settlement, hold, payout y chargebacks.
- `backend/geo/src/main/java/`  
  Geo Core Java 21 con PublicCellService, RoutingPort, MockRoutingPort, privacidad y validación ETA.
- `backend/geo/src/test/java/`  
  Contract tests de Geo P1.3.
- `backend/media/src/main/java/`  
  Media Core Java 21 con WebRtcPort, MockWebRtcPort, room lifecycle, TURN, camera validity y reconnect.
- `backend/media/src/test/java/`  
  Contract tests de Online/Live P1.4.
- `docs/finance/LEDGER_PAYMENTPORT_V1.md`  
  Trazabilidad de las reglas financieras V1.7 y decisiones técnicas explícitas.
- `docs/geo/GEO_ROUTING_ETA_V1.md`  
  Trazabilidad P1.3, privacidad, PostGIS, routing y decisiones técnicas.
- `docs/media/WEBRTC_MEDIA_V1.md`  
  Trazabilidad P1.4, Online/Live, TURN, heartbeat, cámara y reconexión.
- `api/openapi/openapi.yaml`  
  Contrato REST OpenAPI 3.1 del MVP, con endpoints, schemas, error model, auth scopes, idempotencia, paginación y rate limits.
- `api/realtime/protocol.json`  
  Protocolo WebSocket V1 machine-readable, con topics, autorización, secuencia, replay/resync, envelope y 27 eventos V1.7.
- `.github/workflows/state-machines.yml`  
  CI de State Machines con Java 21.
- `.github/workflows/finance.yml`  
  CI del Finance Core con Java 21.
- `.github/workflows/openapi.yml`  
  CI de validación del contrato OpenAPI.
- `.github/workflows/realtime.yml`  
  CI del contrato WebSocket V1.
- `.github/workflows/geo.yml`  
  CI de Geo Core y aserciones estáticas sobre la migración P1.3.
- `.github/workflows/media.yml`  
  CI de Media Core y aserciones estáticas sobre la migración P1.4.

## Estado de hitos

| Hito | Estado |
|---|---|
| Producto / reglas V1.7 | ✅ definido |
| ER físico V1.0 | ✅ diseñado |
| PostgreSQL/PostGIS V1.0 | ✅ versionado |
| State Machines Java 21 | ✅ versionadas |
| Contract tests State Machines | ✅ 34/34 |
| Ledger + PaymentPort Mock | ✅ implementado |
| Contract tests Finance | ✅ 22/22 local |
| OpenAPI REST V1 | ✅ implementado |
| WebSocket V1 | ✅ implementado |
| Geo / routing / ETA V1 | ✅ implementado |
| Contract tests Geo | ✅ 12/12 local |
| WebRTC / Media V1 | ✅ implementado |
| Contract tests Media | ✅ 17/17 local |
| GitHub Actions | ✅ configurado |
| UX / wireframes | ⏭️ siguiente |
| Admin / HumanReviewQueue | pendiente |
| Integración de proveedores reales | pendiente |
| E2E / staging / piloto | pendiente |

## Ejecutar State Machines

```bash
cd backend/state-machines
bash run-tests.sh
```

En Windows:

```powershell
cd backend/state-machines
.\run-tests.ps1
```

## Ejecutar Finance Core

```bash
cd backend/finance
bash run-tests.sh
```

En Windows:

```powershell
cd backend/finance
.\run-tests.ps1
```

## Ejecutar Geo Core

```bash
cd backend/geo
bash run-tests.sh
```

## Ejecutar Media Core

```bash
cd backend/media
bash run-tests.sh
```

## Validar OpenAPI

```bash
npx --yes @redocly/cli@1.34.5 lint api/openapi/openapi.yaml --extends=minimal
```

## Validar WebSocket V1

```bash
python api/realtime/validate_protocol.py
```

## Ejecutar schema PostgreSQL

Desde `database/schema/` con `psql`:

```bash
psql -d tiempojusto -f TiempoJusto_PostgreSQL_Schema_V1_0.sql
psql -d tiempojusto -f ../migrations/V1_1__geo_routing_eta.sql
psql -d tiempojusto -f ../migrations/V1_2__webrtc_media.sql
```

Las migraciones nuevas aún requieren ejecución real contra PostgreSQL 16 + PostGIS antes de considerarse físicamente validadas.

## Próximo hito

**P2.1 Wireframes / UX**, para convertir las reglas congeladas en flujos navegables de onboarding, Home/mapa, filtros, perfil, Proposal, Meta, Auction, Live, Winner, presencial, Online, Wallet y Safety.

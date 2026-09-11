# TiempoJusto

Repositorio técnico del proyecto **TiempoJusto**.

## Estado actual

TiempoJusto ya pasó de definición funcional a una primera integración ejecutable. El repositorio contiene schema PostgreSQL/PostGIS V1, máquinas de estado Java 21, Ledger financiero + PaymentPort Mock, OpenAPI REST V1, WebSocket V1, Geo/ETA V1, WebRTC/Media V1, wireframes UX P2.1, un runtime Spring Boot integrado y un Golden Path Online sandbox ejecutado por CI.

El workflow `Runtime Integration` ejecuta el schema físico y las migraciones sobre PostgreSQL 16 + PostGIS real en CI, compila el reactor Java completo, inicia el JAR integrado, exige `/actuator/health = UP` y recorre el Golden Path Online sandbox vía HTTP.

## Estructura principal

- `database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql`
  Loader ejecutable del schema PostgreSQL/PostGIS V1.0.
- `database/migrations/V1_1__geo_routing_eta.sql`
  Migración P1.3 para privacidad geográfica, ETA y elegibilidad presencial.
- `database/migrations/V1_2__webrtc_media.sql`
  Migración P1.4 para estado de media, cámara válida, no grabación, incidents y reconexión.
- `backend/pom.xml`
  Reactor Maven para State Machines, Finance, Geo, Media y la aplicación integrada.
- `backend/app/`
  Runtime Spring Boot Java 21 con datasource PostgreSQL, health, verificación fail-fast del schema y harness Online sandbox.
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
- `docs/runtime/RUNTIME_INTEGRATION_V1.md`
  Ejecución local, límites de los adapters mock y alcance de la integración runtime V1.
- `docs/runtime/GOLDEN_PATH_ONLINE_V1.md`
  Vertical slice Online sandbox y sus invariantes verificadas por CI.

## Wireframes UX P2.1

Figma editable:
https://www.figma.com/design/xnWgItVIbdxLPmy9i6oJNk

Incluye onboarding/KYC, discovery, mapa aproximado, perfil, Proposal, Disponible Ahora, Meta Ahora, Auction, Live, ganador, presencial, Online, sesión pagada/reconexión, extensión, Wallet/Payout, Safety y Rating.

## Estado de hitos

| Hito | Estado |
|---|---|
| Producto / reglas V1.7 | ✅ definido |
| ER físico V1.0 | ✅ diseñado |
| PostgreSQL/PostGIS V1.0 + V1.1 + V1.2 | ✅ ejecutado en PostgreSQL 16/PostGIS por CI |
| State Machines Java 21 | ✅ 34/34 tests |
| Ledger + PaymentPort Mock | ✅ 22/22 tests |
| OpenAPI REST V1 | ✅ implementado |
| WebSocket V1 | ✅ implementado |
| Geo / routing / ETA V1 | ✅ 12/12 tests |
| WebRTC / Media V1 | ✅ 17/17 tests |
| UX / wireframes P2.1 | ✅ Figma V1 |
| Runtime Spring Boot integrado V1 | ✅ CI ejecutable |
| Golden Path Online sandbox | ✅ CI end-to-end entre módulos |
| GitHub Actions | ✅ configurado |
| Golden Path persistente + endpoints reales | ⏭️ siguiente |
| KYC real | pendiente |
| PaymentPort / payouts reales | pendiente |
| Routing/ETA real | pendiente |
| WebRTC/TURN real | pendiente |
| Frontend funcional | pendiente |
| Admin mínimo / HumanReviewQueue UI | pendiente |
| E2E / security / observability | pendiente |
| Staging / piloto | pendiente |

> La validación PostgreSQL indicada arriba es una validación reproducible de CI, no equivale todavía a staging ni producción. El Golden Path actual usa adapters sandbox/mock y no procesa dinero real.

## Ejecutar módulos

```bash
cd backend/state-machines && bash run-tests.sh
cd backend/finance && bash run-tests.sh
cd backend/geo && bash run-tests.sh
cd backend/media && bash run-tests.sh
```

## Ejecutar runtime integrado

```bash
docker compose up -d postgres

export PGPASSWORD=tiempojusto
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_1__geo_routing_eta.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_2__webrtc_media.sql

mvn -B -f backend/pom.xml -DskipTests package
SPRING_PROFILES_ACTIVE=dev java -jar backend/app/target/tiempojusto-app-1.0.0.jar
```

Health:

```bash
curl http://localhost:8080/actuator/health
```

Golden Path sandbox:

```bash
curl -X POST http://localhost:8080/internal/sandbox/golden-path/online \
  -H 'Content-Type: application/json' \
  -d '{"amountClp":60000,"durationMinutes":30}'
```

## Próximo hito

Convertir el Golden Path sandbox en **application services y endpoints persistentes reales**, manteniendo el mismo recorrido:

`registro/KYC -> Proposal -> Auction -> reserva de fondos -> Online -> FREE_ONLINE -> aceptación bilateral -> PAID_ACTIVE -> settlement -> hold -> payout`.

Los adapters `MockPaymentPort`, `MockRoutingPort` y `MockWebRtcPort` siguen siendo exclusivamente de desarrollo/CI y no representan proveedores reales de producción.

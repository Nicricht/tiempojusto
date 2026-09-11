# TiempoJusto Runtime Integration V1

## Objetivo

Convertir los módulos ya construidos por separado en un runtime ejecutable y comenzar a validar el sistema contra PostgreSQL 16 + PostGIS real, sin fingir que los proveedores externos ya fueron seleccionados.

## Alcance de este hito

- Reactor Maven único bajo `backend/pom.xml`.
- Aplicación Spring Boot Java 21 bajo `backend/app`.
- Spring Boot 4.1.1.
- Dependencias reales entre State Machines, Finance, Geo, Media y la aplicación principal.
- Wiring de desarrollo/CI con `MockPaymentPort`, `MockRoutingPort` y `MockWebRtcPort`.
- Datasource PostgreSQL real.
- Verificación fail-fast de PostGIS y relaciones críticas al iniciar.
- `docker-compose.yml` para PostgreSQL 16 + PostGIS local.
- GitHub Actions que ejecuta físicamente Schema V1.0 + migraciones V1.1 Geo + V1.2 Media.
- Arranque del JAR Spring Boot contra esa base y comprobación de `/actuator/health`.

## Lo que este hito NO declara resuelto

Los adapters mock siguen siendo solo desarrollo/CI. Producción no debe usar:

- `MockPaymentPort` para cobros/payouts reales.
- `MockRoutingPort` para elegibilidad presencial.
- `MockWebRtcPort` como infraestructura media real.

Tampoco se declara seleccionado un proveedor KYC, pagos, routing/mapas o WebRTC/TURN.

## Ejecución local

1. Levantar PostgreSQL/PostGIS:

```bash
docker compose up -d postgres
```

2. Ejecutar schema y migraciones:

```bash
export PGPASSWORD=tiempojusto
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_1__geo_routing_eta.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_2__webrtc_media.sql
```

3. Compilar runtime:

```bash
mvn -B -f backend/pom.xml -DskipTests package
```

4. Iniciar:

```bash
java -jar backend/app/target/tiempojusto-app-1.0.0.jar
```

5. Health:

```bash
curl http://localhost:8080/actuator/health
```

## Fail-fast de base de datos

`DatabaseSchemaVerifier` comprueba que PostGIS esté activo y que existan, como mínimo:

- `iam.app_user`
- `market.proposal`
- `auction.auction`
- `appointment.appointment_session`
- `finance.ledger_entry`
- `finance.payout`
- `geo.route_estimate_snapshot`
- `media.video_participant_state`

Si falta una relación crítica, el runtime no se considera listo.

## Siguiente corte

Después de estabilizar esta integración, el Golden Path prioritario es Online:

`registro -> KYC -> Proposal -> Auction -> reserva de fondos -> Online -> FREE_ONLINE -> aceptación bilateral -> PAID_ACTIVE -> settlement -> hold -> payout`.

Los primeros pasos de ese Golden Path pueden seguir usando adapters sandbox/mock, pero el objetivo siguiente es reemplazar uno por uno KYC y PaymentPort por proveedores reales/sandbox verificables.

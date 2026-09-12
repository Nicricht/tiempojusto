# TiempoJusto

Repositorio técnico del proyecto **TiempoJusto**.

## Estado actual

TiempoJusto ya pasó de definición funcional a una integración ejecutable con una primera vertical slice Online persistente, una vertical slice financiera persistente, Admin mínimo P2.2 para Safety/HumanReview y el primer corte de P2.3 Provider Adapters para pagos. El repositorio contiene schema PostgreSQL/PostGIS, máquinas de estado Java 21, Finance/Ledger core, OpenAPI REST V1, WebSocket V1, Geo/ETA V1, WebRTC/Media V1, wireframes UX P2.1, runtime Spring Boot, application services para el recorrido Online, OAuth2/JWT provider-neutral, Pause/Reconnect/Resume Online persistente, settlement/ledger PostgreSQL V1.5, HumanReviewQueue y bindings de proveedor de pago V1.6.

`Runtime Integration` ejecuta el schema y las migraciones operativas del runtime sobre PostgreSQL 16 + PostGIS real, compila el reactor Java, inicia el JAR, exige `/actuator/health = UP`, mantiene el Golden Path sandbox y recorre el flujo persistente Online. `Payment Provider Adapters` ejecuta Finance + contract tests del adapter, aplica schema + migraciones hasta V1.6 en PostgreSQL 16/PostGIS y compila el backend integrado. `Auth Integration`, `Online Reconnect Integration`, `Persistent Settlement Integration` y `Admin Human Review Integration` continúan validando sus invariantes de forma independiente.

## Estructura principal

- `database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql`
  Loader ejecutable del schema PostgreSQL/PostGIS V1.0.
- `database/migrations/V1_1__geo_routing_eta.sql`
  Migración P1.3 para privacidad geográfica, ETA y elegibilidad presencial.
- `database/migrations/V1_2__webrtc_media.sql`
  Migración P1.4 para estado de media, cámara válida, no grabación, incidents y reconexión.
- `database/migrations/V1_3__oauth2_identity.sql`
  Binding provider-neutral `issuer + subject` hacia el usuario interno de TiempoJusto.
- `database/migrations/V1_4__online_reconnect_persistence.sql`
  Invariantes físicos para segmentos PAID/RECONNECT e interrupciones Online persistentes.
- `database/migrations/V1_5__persistent_session_settlement.sql`
  Settlement de sesión, tracking de reserva, ledger/payout availability y objective payout holds.
- `database/migrations/V1_6__payment_provider_bindings.sql`
  Bindings durables entre UUID internos y referencias opacas del proveedor, sin PAN/CVV ni tokens de tarjeta persistidos.
- `backend/pom.xml`
  Reactor Maven para State Machines, Finance, Geo, Media y la aplicación integrada.
- `backend/app/`
  Runtime Spring Boot Java 21 con datasource PostgreSQL, health, application services, OAuth2 Resource Server, endpoints HTTP, reconnect/settlement/payout watchers, HumanReviewQueue, transactional outbox/audit y seams de provider adapters.
- `backend/state-machines/`
  Máquinas de estado Java 21 y 34 contract tests.
- `backend/finance/`
  Ledger de doble entrada, PaymentPort agnóstico, MockPaymentPort y primer adapter candidato de Mercado Pago con capabilities fail-closed.
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
- `docs/admin/P2_2_ADMIN_HUMAN_REVIEW_QUEUE_V1.md`
  Alcance Admin P2.2: HumanReviewQueue, evidencia, decisiones, appeals, RiskSignal privado y auditoría financiera read-only.
- `docs/providers/P2_3_PAYMENT_PROVIDER_ADAPTERS_V1.md`
  Primer corte P2.3: capabilities, adapter candidato Mercado Pago, Flow como candidato pendiente y bloqueadores antes de mover dinero real.
- `docs/runtime/RUNTIME_INTEGRATION_V1.md`
  Ejecución local, límites de adapters mock y alcance del runtime V1.
- `docs/runtime/GOLDEN_PATH_ONLINE_V1.md`
  Vertical slice Online sandbox original y sus invariantes.
- `docs/runtime/APPLICATION_SERVICES_ONLINE_V1.md`
  Application services, persistencia transaccional, endpoints y límites del flujo Online persistente.
- `docs/runtime/OAUTH2_JWT_AUTH_V1.md`
  Arquitectura OAuth2/OIDC, JWT, mapping IAM y refresh-token adapter.
- `docs/runtime/ONLINE_RECONNECT_PERSISTENCE_V1.md`
  Microcortes, pausa retroactiva de billing, reconexión de 2 minutos, recuperación y resume bilateral persistentes.
- `docs/runtime/PERSISTENT_SETTLEMENT_LEDGER_V1.md`
  Liquidación proporcional, split 80/20, double-entry, payout hold de 60 minutos y política de no-redondeo inventado.

## Wireframes UX P2.1

Figma editable:
https://www.figma.com/design/xnWgItVIbdxLPmy9i6oJNk

Incluye onboarding/KYC, discovery, mapa aproximado, perfil, Proposal, Disponible Ahora, Meta Ahora, Auction, Live, ganador, presencial, Online, sesión pagada/reconexión, extensión, Wallet/Payout, Safety y Rating.

## Estado de hitos

| Hito | Estado |
|---|---|
| Producto / reglas V1.7 | ✅ definido |
| ER físico V1.0 | ✅ diseñado |
| PostgreSQL/PostGIS V1.0 + migraciones V1.1-V1.5 | ✅ ejecutado en PostgreSQL 16/PostGIS por CI |
| Payment provider bindings V1.6 | ✅ migración + validación PostgreSQL/PostGIS en CI P2.3 |
| OAuth identity V1.3 | ✅ migración y lookup IAM |
| Online reconnect persistence V1.4 | ✅ migración y Golden Path en PostgreSQL/PostGIS CI |
| Persistent settlement / Ledger V1.5 | ✅ migración y Golden Path financiero en PostgreSQL/PostGIS CI |
| State Machines Java 21 | ✅ 34/34 tests |
| Ledger + PaymentPort Mock | ✅ 22/22 tests |
| Payment provider adapter candidate V1 | ✅ reserve/capture/release/refund + fail-closed capabilities |
| OpenAPI REST V1 | ✅ implementado |
| WebSocket V1 | ✅ implementado |
| Geo / routing / ETA V1 | ✅ 12/12 tests |
| WebRTC / Media V1 | ✅ 17/17 tests |
| UX / wireframes P2.1 | ✅ Figma V1 |
| Admin / HumanReviewQueue P2.2 | ✅ backend mínimo + CI |
| Evidence Timeline / S5 human decision | ✅ ADMIN activo + AuditEvent/AdminAction |
| Appeals Admin V1 | ✅ cola y resolución auditada |
| Financial Admin audit | ✅ payout/ledger read-only; mutaciones bloqueadas |
| RiskSignal Admin | ✅ privado, no reputación pública |
| Runtime Spring Boot integrado V1 | ✅ CI ejecutable |
| Golden Path Online sandbox | ✅ CI end-to-end entre módulos |
| Application services Online persistentes | ✅ registro/KYC sandbox -> Close Now -> Online -> FINISHED |
| Pause/reconnect/resume Online persistente | ✅ <=5s, pausa retroactiva, 2m, recovery, bilateral resume, timeout |
| Billing por segmentos PAID persistidos | ✅ RECONNECT excluido del tiempo cobrable |
| Settlement de sesión persistente | ✅ proporcional, ZERO_BILLING y bloqueo PENDING_ROUNDING_POLICY |
| Split 80/20 persistente | ✅ HOST PENDING + plataforma REVENUE en double-entry |
| Payout availability persistente | ✅ PENDING_HOLD >=60m -> AVAILABLE sin objective hold activo |
| Objective payout hold | ✅ explícito, con razón/evidencia y liberación auditable |
| Wallet/balance persistente | ✅ PENDING / AVAILABLE / heldForReview / paidOut |
| Transactional audit/outbox | ✅ aplicado a mutaciones críticas del corte |
| OAuth2/JWT Resource Server | ✅ firma, issuer, audience y actor IAM validados en CI |
| Refresh-token OAuth2 adapter | ✅ contrato implementado; proveedor definitivo pendiente |
| Proveedor OAuth2/OIDC definitivo | pendiente |
| KYC real | pendiente |
| PaymentPort / payout provider de producción | pendiente |
| Sandbox real del proveedor de pago | pendiente |
| Protocolo de reemplazo/aumento de reserva para nuevas Bid | pendiente |
| Envío bancario real de payout | pendiente |
| Routing/ETA real | pendiente |
| WebRTC/TURN real | pendiente |
| Regla redondeo proporcional CLP | pendiente |
| Frontend funcional | pendiente |
| Admin UI productiva | pendiente |
| Safety/Admin -> objective payout hold automático por caso | pendiente |
| Live Ticket 70/30 persistente | pendiente |
| Extensiones persistentes | pendiente |
| Security / E2E ampliados / observability | pendiente |
| Staging / piloto | pendiente |

> La validación PostgreSQL indicada es reproducible en CI y no equivale a staging o producción. El adapter candidato de Mercado Pago NO activa dinero real y no contiene credenciales. Los perfiles actuales continúan usando `MockPaymentPort`. El candidate adapter únicamente habilita reserva/autorización, captura final, liberación y refund; `ADJUST_RESERVATION`, `PAYOUT` y creación remota de dispute fallan cerrado hasta demostrar equivalencia segura con V1.7. El ledger y los estados financieros internos sí quedan persistidos en PostgreSQL. OAuth2/JWT valida tokens firmados, aunque todavía no se selecciona el proveedor OAuth2/OIDC de producción. El endpoint HTTP de `media-signal` sigue siendo un seam técnico hasta seleccionar WebRTC/TURN real. El Admin P2.2 implementado es backend operativo mínimo; la UI productiva de administración sigue pendiente.

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
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_3__oauth2_identity.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_4__online_reconnect_persistence.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_5__persistent_session_settlement.sql
psql -h localhost -U tiempojusto -d tiempojusto -v ON_ERROR_STOP=1 -f database/migrations/V1_6__payment_provider_bindings.sql

mvn -B -f backend/pom.xml -DskipTests package
SPRING_PROFILES_ACTIVE=dev java -jar backend/app/target/tiempojusto-app-1.0.0.jar
```

Health:

```bash
curl http://localhost:8080/actuator/health
```

El adapter temporal `X-TJ-Actor-Id` está deshabilitado por defecto y no se crea cuando OAuth2/JWT está habilitado. Solo para desarrollo controlado sin JWT puede activarse con:

```bash
export TJ_AUTH_DEV_HEADER_ENABLED=true
```

Para un proveedor OAuth2/OIDC real se configuran, como mínimo:

```bash
export TJ_AUTH_JWT_ENABLED=true
export TJ_AUTH_JWT_ISSUER='https://issuer.example'
export TJ_AUTH_JWT_AUDIENCE='tiempojusto-api'
export TJ_AUTH_JWT_JWK_SET_URI='https://issuer.example/.well-known/jwks.json'
```

El refresh-token adapter se configura con el token endpoint del proveedor y nunca persiste el refresh token en `iam.oauth_identity`.

## Próximo hito

P2.3 Provider Adapters ya tiene materializado su primer corte de pagos: contrato de capacidades fail-closed, candidate adapter Mercado Pago, transporte HTTPS, persistencia de bindings e integración CI. **Esto no equivale a un proveedor de pago/payout de producción activo.**

Siguiente orden recomendado:

`validación comercial + sandbox real PaymentPort -> protocolo seguro para aumentar/reemplazar reserva de Bid -> payout controlado -> KYC real -> Routing/ETA real -> WebRTC/TURN real -> frontend conectado`

Antes de activar dinero real, TiempoJusto debe comprobar que una Bid nunca quede descubierta y que ningún payout se prometa sin fondos efectivamente capturados. El proveedor/payout definitivo también debe preservar `PENDING -> AVAILABLE >=60m` y `HELD_FOR_REVIEW`.

La liquidación proporcional que produzca una fracción no entera de CLP continúa bloqueada como `PENDING_ROUNDING_POLICY` hasta que producto congele la regla exacta de redondeo.
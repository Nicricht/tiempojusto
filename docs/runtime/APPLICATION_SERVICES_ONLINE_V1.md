# TiempoJusto Application Services Online V1

## Objetivo

Este corte reemplaza la dependencia exclusiva del harness monolítico de Golden Path por application services que escriben el estado de negocio en PostgreSQL y exponen operaciones HTTP separadas.

La fuente funcional continúa siendo TiempoJusto Documento Maestro V1.7. Este corte no cambia reglas de producto ni selecciona proveedores reales.

## Alcance ejecutable

El workflow `Runtime Integration` recorre por HTTP y comprueba directamente en PostgreSQL el siguiente flujo:

`registro sandbox -> KYC adulto sandbox -> Proposal -> Auction Online -> Close Now -> winner -> confirmación -> Online JOIN -> FREE_ONLINE -> aceptación bilateral -> PAID_ACTIVE -> FINISHED`

Se persisten, entre otros:

- `iam.app_user`
- `iam.identity_verification`
- `profile.host_profile`
- `market.proposal`
- `auction.funds_reservation`
- `auction.auction_participant`
- `auction.bid`
- `appointment.appointment`
- `appointment.appointment_session`
- `appointment.session_segment`
- `media.video_room`
- `media.video_participant_state`
- `media.video_participant_event`
- `platform.audit_event`
- `platform.outbox_event`

## Application services

### IdentityApplicationService

Registro/KYC sandbox para CI/desarrollo. Persiste el resultado KYC y nunca documentos ni biometría raw. Solo una identidad adulta verificada pasa a `ACTIVE`.

### ProposalApplicationService

Usa la máquina de estado `Proposal` y las restricciones físicas existentes. Comprueba BIDDER activo/KYC, HOST habilitado para la modalidad, duplicados activos, monto mínimo/múltiplo y vencimiento de 7 días.

### AuctionApplicationService

Abre Auction Online sandbox y ejecuta Close Now de manera transaccional. La reserva financiera ocurre antes del Bid. La base mantiene la autoridad sobre secuencia, validación de reserva, cierre, winning Bid y winner mediante los guards/triggers ya versionados.

Close Now crea el `appointment` y la ventana de confirmación de 3 minutos.

### OnlineApplicationService

Materializa el lifecycle persistente de Online:

1. ganador confirma dentro de 3 minutos;
2. se crea `appointment_session` y `video_room` privado;
3. HOST y BIDDER entran dentro de la ventana de 3 minutos;
4. ambos requieren cámara válida y media fluyendo;
5. al estar ambos válidos empieza FREE_ONLINE por 2 minutos;
6. después se abre la aceptación bilateral de pago por 30 segundos;
7. solo dos aceptaciones válidas permiten `PAID_ACTIVE`;
8. cualquiera de los participantes puede finalizar y el billing se detiene;
9. se persisten `billable_seconds` y el segmento pagado.

El guard SQL V1.2 vuelve a validar que HOST y BIDDER tengan cámara/media válidas al entrar a `PAID_ACTIVE`.

## Seguridad de autenticación

Los endpoints de negocio usan `ActorContext`.

El adapter temporal `X-TJ-Actor-Id` está **deshabilitado por defecto** (`TJ_AUTH_DEV_HEADER_ENABLED=false`). CI lo habilita explícitamente. Si no se habilita y todavía no existe adapter OAuth/JWT, los endpoints protegidos fallan cerrado.

Esto deja un seam claro para reemplazar el header por OAuth2/JWT real sin reescribir los application services.

## Sandbox solamente

Estos endpoints solo existen en perfiles `dev`, `test` o `ci`:

- `POST /api/v1/sandbox/identity/register`
- `POST /api/v1/sandbox/identity/users/{userId}/verify-adult`
- `POST /api/v1/sandbox/funding/me`
- `POST /api/v1/sandbox/auctions`

No son APIs de producción.

## Endpoints de aplicación incorporados

- `POST /api/v1/profiles/{profileId}/proposals`
- `GET /api/v1/proposals/{proposalId}`
- `GET /api/v1/auctions/{auctionId}`
- `POST /api/v1/auctions/{auctionId}/close-now`
- `POST /api/v1/appointments/{appointmentId}/confirm-now`
- `GET /api/v1/sessions/{sessionId}`
- `POST /api/v1/sessions/{sessionId}/online/join`
- `POST /api/v1/sessions/{sessionId}/paid-consent`
- `POST /api/v1/sessions/{sessionId}/finish`

## Idempotencia y autoridad

Close Now requiere `Idempotency-Key`. La tabla `auction.bid` conserva la clave y la base serializa accepted Bid insertion bloqueando la raíz Auction. El transactional outbox se escribe en la misma transacción JDBC que la mutación de aplicación.

La autoridad continúa siendo backend/DB. El cliente no decide winner, tiempo facturable ni estado monetario.

## Redondeo proporcional pendiente

V1.7 exige facturación proporcional por tiempo efectivamente generado, pero el criterio exacto para convertir una fracción no entera de CLP a CLP entero todavía no está congelado.

Por eso `finish` calcula y persiste `billable_seconds`, pero si una finalización parcial produciría una fracción de CLP, responde:

`PENDING_ROUNDING_POLICY`

No captura ni liquida un monto inventando una regla de redondeo.

Si el monto proporcional es exactamente entero, responde `READY_FOR_SETTLEMENT`.

## Lo que todavía no está resuelto

- OAuth2/JWT/refresh real.
- Proveedor KYC real.
- PaymentPort/payout real.
- Ledger financiero persistente como adapter del `FinanceEngine`; el core financiero actual conserva su adapter in-memory para sandbox.
- Routing/ETA real.
- WebRTC/TURN real.
- Pause/reconnect/resume persistente completo en los endpoints de aplicación.
- Normal Bid application service y cadena de backups de winner.
- Proposal update/withdraw application services.
- Regla exacta de redondeo proporcional CLP.
- Staging y producción.

## CI y gate de merge

La prueba de integración usa PostgreSQL 16 + PostGIS, ejecuta Schema V1.0 + V1.1 + V1.2, levanta el JAR Spring Boot y recorre el flujo HTTP persistente. Este corte no debe fusionarse si falla el job `Runtime Integration / postgres-and-app`.

Para evitar esperar minutos reales, CI adelanta exclusivamente los timestamps FREE_ONLINE/PAID mediante SQL después de que las transiciones reales correspondientes ya fueron creadas. Esa aceleración no existe como endpoint de producto.

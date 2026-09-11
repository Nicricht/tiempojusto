# TiempoJusto WebSocket V1

Especificación ejecutable del hito **P1.2 WebSocket**.

## Fuente funcional

El Documento Maestro V1.7 define el catálogo de 27 eventos de tiempo real, declara que WebSocket solo sincroniza UX y que backend/BD conserva la verdad transaccional. V1.7 deja como pendiente técnico los topics, autorización, secuencia, replay/resync y formato versionado. Este directorio materializa exclusivamente ese pendiente.

## Archivos

- `protocol.json`: contrato machine-readable.
- `validate_protocol.py`: contract test sin dependencias externas.

## Conexión

- Endpoint: `/ws/v1`
- Subprotocolo: `tiempojusto.v1`
- Encoding: JSON UTF-8
- Autenticación: JWT ofrecido en `Sec-WebSocket-Protocol` junto al subprotocolo. No se aceptan tokens en query string.
- Heartbeat de aplicación: 25 s.
- Idle timeout técnico: 60 s.

Estas cifras de transporte son decisiones técnicas V1 y pueden ajustarse operacionalmente sin cambiar las reglas de producto.

## Topics

- `user:{userId}`: solo el propio usuario. Eventos privados como rechazo de Bid, suplente, payout y Safety.
- `auction:{auctionId}`: usuarios autenticados autorizados a ver la Auction.
- `session:{sessionId}`: exclusivamente HOST + BIDDER confirmado.
- `live:{liveId}`: HOST o usuario con `LiveAccess` válido.

No existe una suscripción comodín global en el MVP.

## Regla crítica

El WebSocket **no ejecuta mutaciones de negocio**. Pujar, Cierre inmediato, confirmar ganador, Handshake, iniciar/reanudar billing, extensiones, refunds y payouts continúan por REST/application services. El socket publica el resultado ya confirmado por backend.

## Secuencia y entrega

Cada topic mantiene un `sequence` monotónico. El transporte es `at-least-once`, por lo que el cliente debe deduplicar por `eventId` y `topic + sequence`.

Replay corto V1:

- máximo 5 minutos;
- máximo 500 eventos por topic;
- si el hueco ya no puede reproducirse, el servidor emite `RESYNC_REQUIRED`.

El cliente entonces obtiene un snapshot por REST y se vuelve a suscribir desde la secuencia del snapshot. Nunca reconstruye ganador, dinero o billing suponiendo eventos faltantes.

## Frames de control

Cliente: `SUBSCRIBE`, `UNSUBSCRIBE`, `PING`.

Servidor: `READY`, `SUBSCRIBED`, `EVENT`, `RESYNC_REQUIRED`, `ERROR`, `PONG`, `AUTH_EXPIRING`.

## Envelope V1

Todo `EVENT` contiene un envelope con `specVersion`, `schemaVersion`, `eventId`, `type`, `topic`, `sequence`, timestamps de servidor, recurso, visibilidad y `payload`.

Los campos monetarios/timers son informativos para UX. El cliente no los reenvía como verdad de negocio.

## Seguridad

Quedan fuera de eventos de tiempo real: documentos KYC/biometría raw, PAN/CVV, secretos del proveedor, historial GPS exacto y dirección privada salvo que un flujo REST autorizado deba revelarla. La autorización se revalida en cada `SUBSCRIBE`.

La conexión WebSocket tampoco equivale a conexión de media. Para Online/Live, los media heartbeats y reglas de reconexión siguen siendo independientes.

## Eventos V1.7 cubiertos

`AUCTION_STARTED`, `BID_ACCEPTED`, `BID_REJECTED_PRIVATE`, `POZO_UPDATED`, `TIMER_RESET_2M`, `AUCTION_CLOSED`, `CLOSE_NOW_EXECUTED`, `WINNER_SELECTED`, `BACKUP_OFFERED`, `WINNER_CONFIRMED`, `ARRIVAL_CONFIRMED`, `HANDSHAKE_CONFIRMED`, `FREE_PERIOD_STARTED`, `PAID_ACTIVE_STARTED`, `SESSION_PAUSED`, `SESSION_RESUMED`, `SESSION_FINISHED`, `EXTENSION_PROPOSED`, `EXTENSION_COUNTERED`, `EXTENSION_CONFIRMED`, `LIVE_STARTED`, `LIVE_CONNECTION_LOST`, `LIVE_ENDED`, `ONLINE_CONNECTION_LOST`, `BILLING_PAUSED_CONNECTION`, `PAYOUT_AVAILABLE`, `REPORT_STATUS_CHANGED`.

## Validación

```bash
python api/realtime/validate_protocol.py
```

El validador comprueba catálogo exacto V1.7, topics obligatorios, routing, autoridad del backend y reglas base de seguridad/replay.

## Siguiente hito

P1.3 Geo: celdas aproximadas, PostGIS, routing/ETA y separación entre ubicación pública/privada.

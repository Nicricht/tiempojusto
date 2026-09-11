# TiempoJusto Online Reconnect Persistence V1

Fuente funcional: Documento Maestro V1.7, FR080-FR082 y Anexo L.

## Objetivo

Persistir el ciclo de interrupción de una sesión Online pagada sin cambiar las reglas congeladas del producto.

Flujo ejecutable:

`PAID_ACTIVE -> microcorte tolerado <=5s -> interrupción confirmada >5s -> RECONNECTING -> media recuperada -> aceptación bilateral -> PAID_ACTIVE`

Rama terminal:

`RECONNECTING -> 2:00 agotados -> FINISHED_RECONNECT_TIMEOUT`

## Invariantes implementados

- Un microcorte de hasta 5 segundos no pausa el billing.
- Una interrupción confirmada pausa el billing retroactivamente desde el último instante común de media válida, nunca antes del inicio del segmento PAID actual.
- El reloj de reconexión dura exactamente 2 minutos desde la detección confirmada.
- La recuperación técnica de media no reactiva el cobro.
- HOST y BIDDER deben volver a tener cámara/media válidas y aceptar bilateralmente para regresar a `PAID_ACTIVE`.
- Si la media vuelve a caer antes de completar la aceptación bilateral, las aceptaciones anteriores dejan de contar porque el nuevo instante de recuperación reinicia la referencia.
- Al vencer la reconexión, la sesión termina lógicamente en `reconnect_deadline` y solo conserva segundos de segmentos PAID ya generados.
- El audio muteado por sí solo no dispara una interrupción.
- Las llamadas Online privadas mantienen `persistent_recording_enabled=false`.

## Persistencia

Se reutilizan estructuras ya existentes de V1.0/V1.2:

- `appointment.appointment_session`
- `appointment.session_segment`
- `media.video_room`
- `media.video_participant_state`
- `media.video_participant_event`
- `media.media_incident`

La migración `V1_4__online_reconnect_persistence.sql` agrega invariantes de integridad para impedir múltiples segmentos PAID/RECONNECT abiertos y múltiples interrupciones Online simultáneamente no recuperadas.

## Endpoints

- `POST /api/v1/sessions/{sessionId}/online/media-signal`
- `POST /api/v1/sessions/{sessionId}/online/resume-consent`
- `GET /api/v1/sessions/{sessionId}/online/reconnect-state`

El endpoint de media es un seam técnico actual. Mientras `WebRtcPort` siga usando Mock en desarrollo/CI, la señal llega por HTTP desde el participante autenticado. Cuando se seleccione WebRTC/TURN real, el adapter de media deberá alimentar el mismo application service con señales robustas del proveedor. No se analiza ni almacena contenido audiovisual.

## Timeout watcher

`OnlineReconnectTimeoutJob` revisa sesiones vencidas y finaliza el estado persistido aunque ningún cliente vuelva a enviar tráfico. El watcher corre por defecto cada segundo, pero esa frecuencia es un parámetro técnico, no una nueva regla de producto. La regla funcional sigue siendo una ventana exacta de 2 minutos.

## Billing y finish

`/sessions/{sessionId}/finish` ahora calcula el tiempo cobrable desde la suma de segmentos `PAID` persistidos. Los segmentos `RECONNECT` son siempre no cobrables.

La regla exacta de redondeo monetario proporcional CLP sigue pendiente. Si el tiempo parcial genera una fracción de CLP, el estado continúa siendo `PENDING_ROUNDING_POLICY`; este bloque no inventa una regla monetaria nueva.

## Fuera de alcance

- proveedor WebRTC/TURN real;
- frecuencia definitiva de heartbeats del proveedor;
- reglas de Risk/Quality por patrones repetidos de desconexión;
- settlement/ledger persistente de aplicación;
- redondeo CLP definitivo.

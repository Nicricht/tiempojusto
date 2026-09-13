# TiempoJusto WebRTC / TURN real V1

Estado: implementación técnica para #24. No constituye por sí sola evidencia de TURN público en staging.

## Arquitectura

- El navegador usa `RTCPeerConnection` y exige cámara antes de entrar a la sesión ONLINE.
- El backend emite credenciales TURN temporales mediante `CoturnWebRtcPort` usando el mecanismo shared-secret de coturn.
- El secreto TURN permanece únicamente en backend / secret store. El frontend recibe usuario, credential, URLs y expiración de corta duración.
- El signaling 1:1 se intercambia mediante endpoints autenticados de TiempoJusto. `media.webrtc_signal` es `UNLOGGED`, expira en máximo 2 minutos y su data se excluye de backups.
- El signaling puede contener SDP/ICE y por lo tanto metadatos técnicos de red. No contiene audio, video, frames, transcripciones ni credenciales TURN.
- Las sesiones ONLINE privadas mantienen `persistent_recording_enabled=false`.

## Endpoints

- `GET /api/v1/sessions/{sessionId}/online/webrtc/config`
- `POST /api/v1/sessions/{sessionId}/online/webrtc/signals`
- `GET /api/v1/sessions/{sessionId}/online/webrtc/signals?after={sequence}`

Los tres endpoints requieren que el actor autenticado sea HOST o BIDDER de la sesión.

## Autoridad de negocio

WebRTC no decide facturación. El backend sigue siendo autoridad para:

- ventana simultánea de entrada de 3 minutos;
- `FREE_ONLINE` de 2 minutos, persistido como `FREE_ACTIVE`;
- aceptación bilateral antes de `PAID_ACTIVE`;
- tolerancia técnica de microcorte de 5 segundos;
- pausa retroactiva cuando se confirma una interrupción superior a 5 segundos;
- reconexión máxima de 2 minutos;
- recuperación de media sin reactivar billing automáticamente;
- reanudación sólo tras aceptación bilateral;
- finalización y settlement;
- `PENDING_ROUNDING_POLICY` cuando corresponda.

El navegador reporta salud de media, pero no puede crear tiempo facturable por sí mismo.

## Staging

`ops/staging/docker-compose.staging.yml` incluye coturn con puertos 3478 TCP/UDP y un rango UDP de relay 49160-49200. Los valores reales se suministran fuera del repositorio:

- `TJ_TURN_REALM`
- `TJ_TURN_EXTERNAL_IP`
- `TJ_TURN_URLS`
- `TJ_TURN_SHARED_SECRET`
- `TJ_TURN_CREDENTIAL_TTL_SECONDS`

Para demostrar que el tráfico realmente atraviesa TURN se puede compilar el frontend con `TJ_WEBRTC_FORCE_RELAY=true`. Esto transforma `iceTransportPolicy` a `relay` y evita que una prueba exitosa se apoye silenciosamente en una ruta P2P directa.

## Evidencia que todavía requiere infraestructura externa

No cerrar #24 sólo porque compile este bloque. Para considerar cumplido el gate se debe probar en un entorno accesible:

1. dos navegadores reales autenticados conectados a la misma sesión;
2. ambos con cámara válida;
3. candidate pair seleccionado de tipo `relay` al ejecutar la prueba TURN;
4. FREE_ONLINE y consentimiento bilateral sin alterar reglas existentes;
5. microcorte <=5 s sin pausa;
6. corte >5 s entrando a `RECONNECTING`;
7. recuperación sin cobro hasta resume bilateral;
8. timeout de 2 minutos terminando/liquidando;
9. ausencia de grabación persistente y de secretos TURN en logs, DB y frontend build.

Hasta tener esa evidencia, #24 permanece abierto.

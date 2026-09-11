# P1.4 WebRTC / Media V1

## Alcance congelado

Este bloque materializa el contrato técnico de media sin escoger proveedor real. La fuente maestra exige room lifecycle, TURN, media heartbeat, camera validity y reconnect events. El proveedor WebRTC/TURN sigue siendo un gate externo pendiente.

## Online

Flujo técnico complementario al state machine de negocio:

`JOIN_WINDOW -> READY_FOR_FREE -> PAID_ACTIVE -> RECONNECTING -> RECOVERED_AWAITING_BILATERAL_RESUME -> PAID_ACTIVE | ENDED`

La sala se crea con deadline de entrada de 3 minutos. Solo se declara lista para FREE cuando HOST y BIDDER han entrado y ambos tienen cámara/video válidos. La capa media no inicia el cobro por sí sola.

Durante PAID_ACTIVE la cámara y el flujo de video deben seguir válidos. El backend tolera 5 segundos de microcorte. Una interrupción confirmada expone `pauseBillingFrom` usando el último instante común en que ambas señales eran válidas. Ese valor debe alimentar el Session/Billing service, que es la autoridad monetaria.

La reconexión dura 2 minutos. Recuperar media no basta. La capa queda esperando aceptación bilateral. Si el deadline vence, la sala termina y Session liquida solo los segundos previamente facturables.

La videollamada privada mantiene `persistentRecordingEnabled=false`.

## Live

Live sigue separado de Auction. Una pérdida de transmisión abre 2 minutos de reconexión, pero `auctionContinues=true`. La finalización natural o por Cierre inmediato se resuelve en el dominio Live/Auction, no en WebRTC.

No existe replay público y la implementación V1 mantiene `persistentRecordingEnabled=false`. Un fallo atribuible a plataforma/HOST puede originar refund según Finance/Policy, pero el Media Core solo conserva evidencia técnica y atribución, no decide el refund.

## TURN y secretos

`TurnCredentials` son efímeras. No se persisten credenciales TURN, tokens de sala, secretos del proveedor ni payloads audiovisuales. El mock genera valores no utilizables contra infraestructura real.

## PostgreSQL

`V1_2__webrtc_media.sql` agrega:

- estado técnico actual por participante de video privado;
- guard de cámara/media válida al entrar a `PAID_ACTIVE` Online;
- check de 30 s para `paid_acceptance_deadline`;
- hardening de no grabación privada;
- `media_lost_at` y `reconnect_deadline` de 2 min para Live;
- `media.media_incident` sin contenido audiovisual;
- unicidad de `provider_room_ref` para Live.

La migración se valida estáticamente en CI. Debe ejecutarse en PostgreSQL/PostGIS real antes de considerarse validada físicamente.

## Integración pendiente

El siguiente adaptador real deberá evaluarse por estabilidad, regiones, costo, privacidad, TURN, observabilidad, límites, webhooks/eventos y comportamiento ante fallos. Esa selección no se inventa en P1.4.

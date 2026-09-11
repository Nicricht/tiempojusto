# TiempoJusto OpenAPI REST V1

Este directorio contiene el contrato HTTP del MVP de TiempoJusto.

## Fuente y alcance

Las familias de endpoints, reglas de idempotencia económica, autoridad del backend y separación de errores provienen del Documento Maestro V1.7. La V1.7 dejaba como pendiente técnico congelar payloads JSON, códigos de error, versionado, scopes, rate limits y contratos OpenAPI. Este artefacto materializa ese P1.1 sin cambiar reglas funcionales.

El contrato NO declara resueltos los proveedores reales de KYC, PaymentPort, routing/ETA o WebRTC/TURN.

## Base URL

`/api/v1`

## Autoridad

El backend decide dinero, timestamps económicos, orden de pujas, ganador, billing y estados. El frontend nunca envía un monto calculado como verdad contable ni decide transiciones por su cuenta.

## Idempotencia

Toda mutación económica o sensible usa `Idempotency-Key` UUID. Repetir la misma intención con la misma clave debe devolver el mismo resultado. Reutilizar la clave con un payload diferente debe producir conflicto.

## Concurrencia

Los recursos mutables con versionado optimista usan `If-Match`. Auction/Bid puede usar serialización/locking pesimista internamente, pero sigue exigiendo idempotencia en la frontera HTTP.

## Error model

`ApiError.category` separa al menos:

- `VALIDATION`
- `AUTHENTICATION`
- `AUTHORIZATION`
- `ELIGIBILITY`
- `FUNDS`
- `CONFLICT`
- `INVALID_STATE`
- `RATE_LIMIT`
- `PROVIDER`
- `NOT_FOUND`
- `INTERNAL`

El campo `code` contiene el código técnico estable, por ejemplo `FUNDS_INSUFFICIENT`, `IDEMPOTENCY_CONFLICT`, `RESOURCE_VERSION_MISMATCH`, `AUCTION_NOT_ACTIVE` o `SESSION_INVALID_STATE`.

## Scopes técnicos V1

- `identity:read`, `identity:verify`
- `profile:read`, `profile:write`
- `discovery:read`
- `proposal:read`, `proposal:write`
- `availability:write`
- `meta:read`, `meta:write`
- `auction:read`, `bid:write`
- `appointment:read`, `appointment:write`
- `session:read`, `session:write`
- `live:read`, `live:write`, `live:ticket`
- `payments:read`, `payments:write`, `payout:write`
- `reputation:read`, `reputation:write`
- `safety:read`, `safety:write`, `safety:appeal`

Son una decisión de ingeniería V1. Pueden reagruparse en la implementación OAuth2/JWT sin cambiar reglas de producto.

## Rate limits técnicos iniciales

- lectura: `120/min/user`
- escritura: `30/min/user`
- pujas: `30/min/user/auction`
- auth: `10/min/IP`
- reportes/apelaciones: `10/hour/user`
- payout: `5/hour/user`

Son defaults operacionales y deben ajustarse con métricas reales, fraude y capacidad.

## Paginación

Los listados usan cursor con `cursor` y `limit` de 1 a 50. No se usa offset para flujos que pueden cambiar rápidamente.

## Familias cubiertas

Identity, Profiles, Discovery, Proposal, Disponible Ahora, Meta Ahora, Auction/Bid, Appointment, Arrival, Handshake, Session, extensiones, Live/Ticket, Finance, Reputation y Safety/Appeals.

## Reglas congeladas reflejadas

- Proposal permanece separada de Bid.
- Auction no acepta como verdad ningún monto o ganador calculado por el cliente.
- Bid requiere idempotencia y validación server-side de fondos/riesgo.
- Cierre inmediato es una mutación económica idempotente.
- Arrival aporta evidencia, pero el reloj de servidor sigue siendo autoritativo.
- FREE_ONLINE no genera billing y el paso a PAID_ACTIVE requiere consentimiento bilateral.
- Las extensiones son +15/+30 y la reserva/cobro se resuelve server-side.
- Ticket Live calcula precio/cutoff en backend.
- Balance distingue PENDING, AVAILABLE, HELD_FOR_REVIEW y PAID_OUT.
- Reportar no prueba culpabilidad y Safety conserva S0-S5/apelación.

## Validación

GitHub Actions ejecuta Redocly CLI sobre `openapi.yaml`.

```bash
npx --yes @redocly/cli@1.34.5 lint api/openapi/openapi.yaml --extends=minimal
```

## Siguiente bloque

P1.2 WebSocket definitivo: topics por Auction/Session/User/Live, autorización por recurso, secuencia, replay corto/resync y versionado de eventos.

# TiempoJusto KYC Sandbox Real V1

Fuente funcional: Documento Maestro V1.7.

## Estado

Este corte deja el runtime preparado para ejecutar el proveedor Veriff con credenciales sandbox reales, pero **no afirma que una sesión externa real ya haya sido ejecutada**. El gate externo se mantiene abierto hasta contar con una integración sandbox contratada/configurada, credenciales fuera del repositorio y un endpoint HTTPS público para recibir webhooks.

## Qué queda materializado

- `IdentityVerificationPort` provider-neutral.
- Adapter Veriff opt-in mediante `TJ_KYC_PROVIDER=veriff`.
- `POST /v1/sessions` para crear sesión alojada por el proveedor.
- `GET /v1/sessions/{id}/decision` con HMAC de session id.
- Validación de `X-AUTH-CLIENT` + `X-HMAC-SIGNATURE` en webhooks.
- Validación HMAC del **raw response body** de las respuestas API del proveedor antes de confiar en su contenido.
- Persistencia exclusiva de referencias opacas y estado normalizado.
- DOB usada transitoriamente para comprobar mayoría de edad y persistida como `NULL` en el flujo real.
- Inbox durable de webhooks firmado con SHA-256, sin guardar el payload crudo.
- ACK rápido del webhook separado de la consulta de decisión al proveedor.
- Reconciliación asíncrona con retry, `FOR UPDATE SKIP LOCKED` y estados de procesamiento.
- Detección explícita de webhook recibido antes de que exista la sesión local (`PENDING_LOCAL_SESSION`).
- Reintento ante timeout/rate limit/indisponibilidad/firma inválida de respuesta (`PENDING_PROVIDER`).
- Deduplicación de webhook por proveedor + SHA-256 del payload.
- Protección contra conflicto entre una decisión nueva y un estado local terminal ya persistido.
- `VERIFIED` activa solo una cuenta todavía `PENDING_VERIFICATION`; nunca reactiva `RESTRICTED`, `SUSPENDED` o `CLOSED`.
- `REJECTED` no activa la cuenta.
- AuditEvent y Outbox para recepción y aplicación de decisiones.

## Persistencia V1.10

`database/migrations/V1_10__kyc_webhook_reconciliation.sql` amplía `iam.identity_provider_event` con:

- `processing_status`;
- `attempt_count`;
- `next_attempt_at`;
- `last_error_code`;
- `last_error_detail`.

Estados internos:

```text
RECEIVED
PROCESSING
PENDING_LOCAL_SESSION
PENDING_PROVIDER
APPLIED
CONFLICT
FAILED
```

Ninguno de estos campos contiene documento, selfie, video, plantilla biométrica ni payload KYC crudo.

## Semántica del webhook

El endpoint:

```text
POST /api/v1/webhooks/kyc/{provider}
```

hace únicamente el trabajo necesario para autenticar y aceptar el evento:

1. valida `X-AUTH-CLIENT`;
2. valida `X-HMAC-SIGNATURE` contra el body crudo;
3. extrae la referencia opaca de sesión;
4. calcula SHA-256;
5. inserta/deduplica el envelope;
6. responde 200.

La llamada externa a `GET /decision` ocurre después en el worker de reconciliación. Esto evita mantener abierto el webhook mientras el proveedor procesa una consulta y permite absorber entrega al-menos-una-vez y eventos fuera de orden.

## Reglas de edad e identidad

El mapping vigente permanece:

- `approved` + código `9001` + DOB válida >=18 -> `VERIFIED`;
- `approved` sin prueba suficiente de edad -> `REVIEW`;
- menor de 18 -> `REJECTED`;
- `declined` -> `REJECTED`;
- `resubmission_requested` -> `REVIEW`;
- `expired` / `abandoned` -> `EXPIRED`;
- otros -> `PENDING`.

TiempoJusto no usa estimación visual de edad como sustituto del KYC legal y este corte no agrega inferencia/almacenamiento de sexo o género.

## CI

`KYC Provider Adapters` prueba localmente, con un stub compatible y respuestas HMAC firmadas:

- creación de sesión;
- validación de firma de respuesta API;
- webhook firmado válido;
- webhook HMAC inválido -> 401;
- webhook duplicado idempotente;
- webhook fuera de orden antes de la sesión local y convergencia posterior;
- `VERIFIED` adulto + activación de cuenta;
- `REJECTED` + cuenta no activada;
- transient 429 + retry interno;
- respuesta API con HMAC adulterado + rechazo + retry + convergencia;
- DOB no persistida;
- perfil HOST permanece `DRAFT`;
- ausencia de columnas de payload/documento/biometría cruda;
- AuditEvent/Outbox de la decisión.

## Gate externo pendiente para cerrar #23

Se cierra solo cuando exista evidencia de una ejecución **contra Veriff sandbox real**:

1. credenciales sandbox almacenadas fuera de Git/GitHub;
2. BaseURL real de la integración;
3. callback/redirect HTTPS real;
4. webhook decision HTTPS real configurado en el portal;
5. creación de una sesión real;
6. una decisión `VERIFIED` real de adulto;
7. una decisión `REJECTED` real o caso de rechazo documentado por el proveedor;
8. recepción y validación de `X-HMAC-SIGNATURE` real;
9. reenvío/deduplicación confirmado;
10. evidencia DB de que solo referencias/estado normalizado fueron persistidos.

Hasta cumplir ese gate, #23 debe permanecer abierto.

## Referencias oficiales verificadas en septiembre de 2026

- Veriff `POST /v1/sessions`: https://devdocs.veriff.com/apidocs/v1sessions
- Veriff `GET /v1/sessions/{id}/decision`: https://devdocs.veriff.com/apidocs/v1sessionsiddecision-1
- Veriff Webhooks Guide: https://devdocs.veriff.com/docs/webhooks-guide
- Veriff HMAC Authentication and Endpoint Security: https://devdocs.veriff.com/v1/docs/hmac-authentication-and-endpoint-security

La documentación oficial indica que los webhooks pueden llegar fuera de orden, usan entrega al-menos-una-vez, esperan respuesta 200 rápida y firman el body crudo con HMAC-SHA256. También documenta HMAC de session id para el GET de decisión y firma del body en respuestas API.

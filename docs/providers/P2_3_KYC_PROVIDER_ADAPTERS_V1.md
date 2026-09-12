# TiempoJusto P2.3 KYC Provider Adapters V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Materializar un seam KYC provider-neutral para identidad y mayoría de edad sin convertir a TiempoJusto en custodio de documentos o biometría cruda.

Este corte **no activa un proveedor KYC de producción**. Mantiene a Veriff como candidato opt-in, con contrato provider-neutral, persistencia mínima, reconciliación durable y CI end-to-end con stub compatible.

## Reglas que conserva

- TiempoJusto es solo para personas adultas.
- La cuenta no pasa de `PENDING_VERIFICATION` a `ACTIVE` sin decisión normalizada `VERIFIED` y `verifiedAdult=true`.
- KYC nunca puede reactivar por sí solo una cuenta `RESTRICTED`, `SUSPENDED` o `CLOSED`.
- No se almacenan documentos KYC crudos, selfies/videos del proveedor ni plantillas biométricas.
- No se persiste la URL/token de sesión del proveedor.
- El webhook crudo no se persiste; solo SHA-256, referencia opaca y estado normalizado.
- El `dateOfBirth` recibido del proveedor se usa transitoriamente para comprobar >=18 y no se persiste en el flujo real.
- El perfil HOST permanece `DRAFT` después del KYC. Verificar identidad no equivale a aprobar contenido/perfil.

## Contrato provider-neutral

`backend/identity/` contiene:

- `IdentityVerificationPort`
- `IdentityProviderCapabilities`
- `VeriffDecisionMapper`
- contract tests Java 21

Capacidades mínimas fail-closed para V1:

- identidad documental;
- prueba de mayoría de edad;
- webhooks firmados;
- documentos crudos permanecen en el proveedor;
- biometría cruda permanece en el proveedor.

Liveness no se marca como obligatorio en este corte porque depende del producto/plan contratado del proveedor y no se debe fingir una capacidad no demostrada.

## Adapter candidato Veriff

El adapter se activa solo con:

```text
TJ_KYC_PROVIDER=veriff
TJ_KYC_CALLBACK_URL=https://...
TJ_KYC_VERIFF_BASE_URL=https://...
TJ_KYC_VERIFF_API_KEY=...
TJ_KYC_VERIFF_SHARED_SECRET=...
```

Sin esas propiedades, el runtime no crea el adapter Veriff.

El candidato usa el flujo documentado por Veriff:

1. `POST /v1/sessions` para crear una sesión y devolver al cliente la URL alojada por el proveedor.
2. Webhook firmado para avisar que existe un cambio.
3. `GET /v1/sessions/{id}/decision` para consultar la decisión normalizada.
4. `X-AUTH-CLIENT` y `X-HMAC-SIGNATURE` para autenticación/firma donde corresponde.
5. Las respuestas API se aceptan solo si su `X-AUTH-CLIENT` y HMAC del body son válidos.

Referencias oficiales verificadas en septiembre de 2026:

- https://devdocs.veriff.com/apidocs/v1sessions
- https://devdocs.veriff.com/apidocs/v1sessionsiddecision-1
- https://devdocs.veriff.com/docs/webhooks-guide
- https://devdocs.veriff.com/v1/docs/hmac-authentication-and-endpoint-security

La guía oficial indica entrega de webhooks al-menos-una-vez, posibilidad de entrega fuera de orden y necesidad de responder 200 rápidamente. Por eso el webhook se persiste primero y la consulta de decisión ocurre en un worker posterior.

## Mapping de decisión

- `approved` + código `9001` + DOB válida >=18 -> `VERIFIED`.
- `approved` sin prueba suficiente de edad -> `REVIEW`.
- menor de 18 -> `REJECTED`.
- `declined` -> `REJECTED`.
- `resubmission_requested` -> `REVIEW`.
- `expired` / `abandoned` -> `EXPIRED`.
- otros estados -> `PENDING`.

TiempoJusto guarda solo el resultado necesario para elegibilidad, no el documento que lo produjo.

## Persistencia

`database/migrations/V1_7__identity_provider_bindings.sql` agrega:

- `iam.identity_provider_session`
- `iam.identity_provider_event`
- índice único para impedir más de una verificación abierta por usuario/proveedor.

`database/migrations/V1_10__kyc_webhook_reconciliation.sql` agrega estado durable de procesamiento, intentos y retry schedule al envelope del webhook.

`identity_provider_event` contiene únicamente hash SHA-256 del payload y metadatos normalizados para idempotencia/auditoría. No contiene el payload crudo.

## Endpoints

Autenticados:

```text
POST /api/v1/identity/verifications
GET  /api/v1/identity/verifications/latest
```

Proveedor:

```text
POST /api/v1/webhooks/kyc/{provider}
```

El webhook no requiere JWT de usuario porque proviene del proveedor, pero debe superar la validación criptográfica del adapter antes de cualquier persistencia. Después del ACK, el worker reconcilia la decisión con retry y deduplicación.

## Sexo / género

Este corte **no agrega ni infiere sexo/género**.

Aunque algunos proveedores pueden devolver campos o estimaciones relacionadas, V1.7 no congeló todavía un atributo físico de elegibilidad de mercado que autorice almacenarlo o inferirlo. El adapter ignora esos campos. Si el producto decide hacerlo obligatorio, debe existir primero una decisión funcional/legal explícita sobre fuente, exactitud, finalidad, retención y tratamiento de ese dato.

## CI

`KYC Provider Adapters` valida:

- contract tests de Identity Core;
- PostgreSQL 16/PostGIS + migraciones hasta V1.10;
- compilación/test del backend completo;
- creación de sesión contra stub Veriff compatible;
- firma HMAC del request de decisión;
- firma HMAC de respuestas API;
- webhook HMAC válido;
- webhook HMAC inválido -> 401;
- `VERIFIED` adulto y activación solo de cuenta pendiente;
- `REJECTED` sin activación;
- DOB no persistida;
- perfil HOST sigue `DRAFT`;
- deduplicación de webhook;
- webhook fuera de orden antes de la sesión local;
- retry tras 429;
- rechazo/retry de respuesta provider con firma adulterada;
- AuditEvent/Outbox.

## Bloqueadores antes de cerrar #23 / producción

- sandbox real con credenciales Veriff fuera del repositorio;
- BaseURL real de la integración;
- callback y webhook HTTPS públicos configurados en el portal;
- evidencia externa `VERIFIED` y `REJECTED`;
- DPA, privacidad, retención y subprocesadores;
- cobertura comercial real en los países del piloto;
- rotación de secretos según configuración final;
- observabilidad sin registrar PII ni secretos.

El procedimiento y gate exacto están en `docs/providers/KYC_SANDBOX_REAL_V1.md`.

Por lo tanto, `VeriffIdentityVerificationAdapter` sigue siendo un **candidate adapter**, no una afirmación de proveedor definitivo aprobado.

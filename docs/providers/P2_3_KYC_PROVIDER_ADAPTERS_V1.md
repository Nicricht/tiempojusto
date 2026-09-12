# TiempoJusto P2.3 KYC Provider Adapters V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Materializar un seam KYC provider-neutral para identidad y mayoría de edad sin convertir a TiempoJusto en custodio de documentos o biometría cruda.

Este corte **no activa un proveedor KYC de producción**. Deja un candidato Veriff deshabilitado por defecto, un contrato provider-neutral, persistencia mínima y CI end-to-end con un stub compatible.

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

`backend/identity/` introduce:

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

Referencias oficiales consultadas en septiembre de 2026:

- https://devdocs.veriff.com/apidocs/v1sessions
- https://devdocs.veriff.com/apidocs/v1sessionsiddecision-1
- https://devdocs.veriff.com/docs/webhooks-guide

## Mapping de decisión

- `approved` + código `9001` + DOB válida >=18 -> `VERIFIED`.
- `approved` sin prueba suficiente de edad -> `REVIEW`.
- menor de 18 -> `REJECTED`.
- `declined` -> `REJECTED`.
- `resubmission_requested` -> `REVIEW`.
- `expired` / `abandoned` -> `EXPIRED`.
- otros estados -> `PENDING`.

TiempoJusto guarda solo el resultado necesario para elegibilidad, no el documento que lo produjo.

## Persistencia V1.7

`database/migrations/V1_7__identity_provider_bindings.sql` agrega:

- `iam.identity_provider_session`
- `iam.identity_provider_event`
- índice único para impedir más de una verificación abierta por usuario/proveedor.

`identity_provider_event` contiene únicamente hash SHA-256 del payload y metadatos normalizados para idempotencia/auditoría.

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

El webhook no requiere JWT de usuario porque proviene del proveedor, pero debe superar la validación criptográfica del adapter antes de cualquier mutación.

## Sexo / género

Este corte **no agrega ni infiere sexo/género**.

Aunque algunos proveedores pueden devolver campos o estimaciones relacionadas, V1.7 no congeló todavía un atributo físico de elegibilidad de mercado que autorice almacenarlo o inferirlo. El adapter ignora esos campos. Si el producto decide hacerlo obligatorio, debe existir primero una decisión funcional/legal explícita sobre fuente, exactitud, finalidad, retención y tratamiento de ese dato.

## CI

`KYC Provider Adapters` valida:

- 7/7 contract tests de Identity Core;
- schema PostgreSQL 16/PostGIS + migraciones V1.1-V1.7;
- compilación de todo el backend;
- creación de sesión contra un stub Veriff compatible;
- HMAC del request de decisión;
- webhook HMAC válido;
- activación solo para adulto verificado;
- DOB no persistida;
- perfil HOST sigue DRAFT;
- ningún media ficticio se crea por KYC;
- deduplicación de webhook;
- HMAC inválido -> 401.

## Bloqueadores antes de producción

- contrato/comercial y cobertura real del proveedor en los países del piloto;
- sandbox real con credenciales del proveedor;
- DPA, privacidad, retención y subprocesadores;
- configuración productiva de callback/webhooks;
- pruebas de reintentos, demoras, indisponibilidad y decisiones manuales;
- validación de response signatures y rotación de secretos según configuración final;
- observabilidad sin registrar PII ni secretos.

Por lo tanto, `VeriffIdentityVerificationAdapter` es un **candidate adapter**, no una afirmación de proveedor definitivo aprobado.

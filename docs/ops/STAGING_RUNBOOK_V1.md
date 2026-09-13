# TiempoJusto Staging / Operación V1

Fuente funcional: Documento Maestro V1.7. Este runbook no agrega reglas de negocio. Mantiene ONLINE-first, backend autoritativo, ledger inmutable, split 80/20, hold mínimo de 60 minutos y `PENDING_ROUNDING_POLICY` sin inventar redondeo CLP.

## 1. Objetivo

El staging debe reproducir la frontera operativa del MVP ONLINE con HTTPS, frontend, backend, PostgreSQL/PostGIS, autenticación JWT/OIDC, proveedores sandbox, migraciones controladas, observabilidad, backups y restore. Ningún secreto se guarda en Git.

El stack reproducible vive en `ops/staging/docker-compose.staging.yml`. Solo el gateway Caddy publica puertos 80/443. PostgreSQL, backend, management/Prometheus, Alertmanager y backups permanecen en la red privada del stack.

## 2. Variables obligatorias fuera del repositorio

Configurar en el secret store del host/orquestador, nunca en commits, `VITE_*`, logs o tickets públicos:

- `TJ_STAGING_HOST`
- `TJ_DB_PASSWORD`
- `TJ_AUTH_JWT_ISSUER`
- `TJ_AUTH_JWT_AUDIENCE`
- `TJ_AUTH_JWT_JWK_SET_URI`
- credenciales OAuth refresh si se habilita refresh
- `TJ_KYC_PROVIDER` y credenciales sandbox del proveedor
- `TJ_PAYMENT_PROVIDER` y credenciales sandbox del proveedor
- secretos de firma de webhooks KYC/Payment
- `TJ_PAYMENT_SANDBOX_PROBE_KEY` solo si se habilita el probe interno
- configuración secreta del canal real de alertas

En staging `TJ_AUTH_DEV_HEADER_ENABLED=false`, `TJ_AUTH_JWT_ENABLED=true`, `TJ_RATE_LIMIT_ENABLED=true` y `TJ_ENVIRONMENT=staging` son obligatorios.

## 3. DNS y HTTPS

1. Crear DNS para `TJ_STAGING_HOST` apuntando al host del stack.
2. Permitir inbound TCP 80/443 y UDP 443 si se desea HTTP/3.
3. Ejecutar el stack con `docker compose -f ops/staging/docker-compose.staging.yml up -d --build`.
4. Caddy obtiene y renueva el certificado HTTPS automáticamente.
5. Verificar `https://$TJ_STAGING_HOST/healthz` y confirmar HTTP 200/UP.

`/internal/**` y `/actuator/**` no se enrutan por el edge público. Prometheus consume `backend:8081/actuator/prometheus` dentro de la red privada.

## 4. Migraciones controladas

`ops/db/migrate.sh` aplica exactamente V1.0 y V1.1...V1.10 en orden. Cada versión se registra en `public.tj_schema_migration` con SHA-256.

Reglas operativas:

- ejecutar una sola instancia de migrator por despliegue;
- una migración ya aplicada debe conservar el mismo checksum;
- checksum diferente en una versión aplicada bloquea el despliegue;
- backend arranca solo después de `migrate` exitoso;
- nunca editar una migración ya aplicada en staging; crear una versión nueva;
- nunca ejecutar DDL manual para “arreglar rápido” producción/staging.

## 5. Health, logs y correlation id

Spring Boot expone internamente:

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`

Los logs de consola usan formato JSON estructurado. Cada request recibe `X-Request-Id`; un id seguro enviado por el cliente se conserva y un valor inválido se reemplaza. El mismo `requestId` entra al MDC para correlacionar logs. No agregar tokens, documentos KYC, biometría, payloads crudos de proveedor ni material audiovisual a logs.

## 6. Métricas y alertas mínimas

Prometheus recoge métricas de dominio de baja cardinalidad para `auth`, `auction`, `session`, `payment_webhook`, `kyc_webhook`, `payout`, `admin` e `identity`. No etiqueta métricas con user IDs, Auction IDs, Session IDs ni provider references.

También existen contadores de settlement y liberación de payout. `ops/observability/alerts.yml` cubre backend caído, ratio 5xx, fallos Auth/Auction/Session, webhooks KYC/Payment rechazados, retries de settlement/payout y ráfagas de rate limiting.

El repositorio contiene un Alertmanager base sin destino externo. Antes de declarar alert delivery probado, el despliegue debe inyectar mediante secret/config externa un receiver real y ejecutar una alerta de prueba.

## 7. Rate limiting

Los límites implementados son los defaults técnicos ya congelados en `api/openapi/README.md`:

- lectura: 120/min/user;
- escritura: 30/min/user;
- Bid: 30/min/user/Auction;
- auth: 10/min/IP;
- reportes/apelaciones: 10/hour/user;
- payout: 5/hour/user.

El limiter dentro del backend es defensivo y por instancia. En una futura topología multi-replica debe existir además un limiter distribuido/edge con las mismas políticas. Webhooks firmados se excluyen del limiter de usuario para no romper reintentos legítimos de proveedor.

## 8. CORS, CSRF y sesión

La API usa `Authorization: Bearer` y `SessionCreationPolicy.STATELESS`; no usa cookie de sesión del backend. Por eso CSRF está deshabilitado en esta frontera. CORS usa una lista exacta `TJ_CORS_ALLOWED_ORIGINS`; no se habilita wildcard ni credentials. En el staging mismo-origen la lista debe ser `https://$TJ_STAGING_HOST`.

JWT productivo/staging exige issuer, audience y JWKS. HMAC queda restringido a `ci/dev/test` por el runtime existente.

## 9. Backups

El servicio `backup` ejecuta `ops/db/backup.sh` cada 6 horas por defecto. El intervalo se puede cambiar con `TJ_BACKUP_INTERVAL_SECONDS`; retención default 14 días.

Cada backup:

- usa `pg_dump --format=custom`;
- no incluye ownership/ACL del host;
- se escribe primero como temporal;
- se mueve atómicamente al nombre final;
- genera sidecar SHA-256;
- aplica permisos restrictivos al artefacto.

La ubicación del volumen/objeto debe tener cifrado at-rest y política de acceso separada del runtime. Para un piloto real, copiar además los backups a almacenamiento externo al host de staging.

## 10. Restore

`ops/db/restore.sh` se niega a restaurar sobre `PGDATABASE`. Siempre restaura a una base nueva y requiere `TJ_RESTORE_CONFIRM=YES`.

Procedimiento:

1. elegir backup y verificar su `.sha256`;
2. definir un nombre de DB nuevo en `TJ_RESTORE_DATABASE`;
3. ejecutar restore;
4. validar `public.tj_schema_migration`, PostGIS, usuarios de prueba y Golden Path;
5. solo después cambiar de forma controlada la URL/secret de DB del backend;
6. conservar la DB anterior hasta cerrar la ventana de rollback.

CI ejecuta backup + restore real sobre PostgreSQL/PostGIS y comprueba un registro marcador, el historial de 11 versiones y la extensión PostGIS.

## 11. Incidente

Para un incidente de seguridad o integridad financiera:

1. preservar `requestId`, timestamps, IDs internos y AuditEvent relacionados;
2. no editar ledger, payout, bids o settlement manualmente;
3. si existe riesgo de doble cobro/captura, detener el backend antes de cualquier intervención destructiva;
4. conservar provider references y evidencia mínima, nunca secretos ni payloads crudos innecesarios;
5. revisar estado provider -> binding -> ledger;
6. para KYC/Payment usar la reconciliación idempotente y webhooks firmados existentes;
7. si hay discrepancia financiera, mantener el caso bloqueado/observado hasta resolución auditable;
8. documentar causa, rango temporal, requests afectados, mitigación y verificación posterior.

Un `report` por sí solo no equivale a culpabilidad ni a payout hold objetivo.

## 12. Rollback

Código: desplegar el commit/imágen anterior compatible y confirmar readiness + smoke tests. No revertir una migración ya aplicada mediante SQL improvisado.

Datos: si una migración o corrupción exige volver a datos anteriores, restaurar un backup en una base nueva según la sección 10 y cambiar la conexión solo después de validación. La estrategia es forward-fix o restore-to-new-DB, nunca overwrite ciego de la DB activa.

Después del rollback ejecutar: auth, consulta Auction, consulta Session, webhook signature checks, settlement determinista sin fracción, payout hold/release y balance de ledger.

## 13. Gate externo pendiente

Este repositorio puede demostrar build, migración, health, métricas, alert rules, rate limiting, scanning, backup y restore en CI. #27 solo puede cerrarse cuando exista evidencia externa de:

- host + DNS HTTPS real;
- OAuth/OIDC sandbox real con JWKS;
- KYC sandbox real y Payment sandbox real con secretos fuera de Git;
- WebRTC/TURN real para completar la sesión ONLINE;
- webhooks reales llegando por HTTPS y validando firma;
- canal real de alertas recibiendo una prueba;
- backup automatizado del staging real y restore probado desde ese artefacto;
- Golden Path staging completo contra proveedores sandbox.

No declarar esos puntos como completados por configuración o mocks.

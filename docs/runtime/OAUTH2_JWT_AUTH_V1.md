# OAuth2 / JWT Auth V1

Fuente funcional: `TiempoJusto_Documento_Maestro_V1_7_INTEGRAL_ULTRA_DETALLADO.docx`.

## Objetivo

Reemplazar el header técnico `X-TJ-Actor-Id` como mecanismo normal de identidad por autenticación OAuth2/OIDC con access tokens JWT firmados y validados criptográficamente por el backend.

Este corte no selecciona todavía un proveedor comercial de identidad. Mantiene el backend provider-neutral mediante `issuer + subject` y JWKS.

## Flujo productivo esperado

1. El cliente obtiene un access token y, cuando corresponda, un refresh token desde el proveedor OAuth2/OIDC seleccionado.
2. El cliente llama a TiempoJusto con `Authorization: Bearer <access_token>`.
3. Spring Security valida firma, expiración, issuer y audience.
4. `JwtActorContext` toma `iss + sub` del token.
5. `iam.oauth_identity` resuelve esa identidad externa al `iam.app_user` interno.
6. La cuenta debe estar `ACTIVE` para ejecutar operaciones de negocio.
7. Controllers y application services siguen trabajando con el UUID interno de TiempoJusto, no con IDs externos.

## Migración V1.3

`database/migrations/V1_3__oauth2_identity.sql` crea `iam.oauth_identity`.

La tabla guarda únicamente:

- `user_id`
- `issuer`
- `subject`
- timestamps de vínculo/autenticación/revocación

No guarda access tokens, refresh tokens, passwords, documentos KYC ni biometría raw.

## Validación JWT

Variables:

- `TJ_AUTH_JWT_ENABLED=true`
- `TJ_AUTH_JWT_ISSUER`
- `TJ_AUTH_JWT_AUDIENCE`
- `TJ_AUTH_JWT_JWK_SET_URI`

Producción debe usar JWKS publicado por el proveedor OAuth2/OIDC.

Existe `TJ_AUTH_JWT_HMAC_SECRET_B64` únicamente para `ci/dev/test`. El runtime rechaza esa modalidad fuera de dichos perfiles.

## ActorContext

Con JWT habilitado, `JwtActorContext` es la fuente de identidad del request.

El antiguo `HeaderActorContext` solo se crea cuando JWT está deshabilitado. Además, `TJ_AUTH_DEV_HEADER_ENABLED` continúa en `false` por defecto.

Por lo tanto, activar OAuth2/JWT impide utilizar `X-TJ-Actor-Id` como bypass.

## Endpoint de identidad

`GET /api/v1/auth/me`

Devuelve el usuario interno resuelto, `publicId`, rol y estado de cuenta. Requiere Bearer token cuando JWT está habilitado.

## Refresh token

`POST /api/v1/auth/refresh`

El backend implementa un adapter OAuth2 estándar para `grant_type=refresh_token` contra el token endpoint configurado:

- `TJ_AUTH_REFRESH_ENABLED=true`
- `TJ_AUTH_REFRESH_TOKEN_URI`
- `TJ_AUTH_REFRESH_CLIENT_ID`
- `TJ_AUTH_REFRESH_CLIENT_SECRET` opcional

Si existe client secret se usa autenticación HTTP Basic del cliente. Si no existe, se envía `client_id` en el formulario, apropiado para un cliente público cuando el proveedor lo soporte.

El refresh token se reenvía al proveedor y no se persiste en `iam.oauth_identity`. La respuesta se entrega con `Cache-Control: no-store` y `Pragma: no-cache`.

La rotación, expiración y revocación real del refresh token quedan bajo autoridad del proveedor OAuth2/OIDC elegido. TiempoJusto no inventa un formato propietario de refresh token.

## CI

`.github/workflows/auth-integration.yml` valida:

- PostgreSQL 16/PostGIS + Schema V1.0 + V1.1 + V1.2 + V1.3;
- firma JWT HS256 con una clave efímera generada dentro del job;
- issuer y audience válidos;
- resolución `iss + sub -> iam.app_user`;
- `X-TJ-Actor-Id` no funciona cuando JWT está activo;
- token manipulado devuelve 401;
- `/api/v1/auth/me` resuelve la cuenta correcta;
- contrato OAuth2 de refresh y headers `no-store`.

El stub OAuth usado para refresh existe solo bajo perfil `ci`. No es un proveedor de producción.

## Pendiente para producción

- seleccionar proveedor OAuth2/OIDC definitivo;
- configurar issuer, JWKS, audience y cliente reales;
- definir el flujo de onboarding que crea el vínculo `iam.oauth_identity` después de registro/verificación;
- definir almacenamiento del refresh token en frontend/BFF según la arquitectura final del cliente;
- revocación/logout contra el proveedor seleccionado;
- rotación de secretos/keys y runbooks operativos;
- pruebas de seguridad específicas del proveedor.

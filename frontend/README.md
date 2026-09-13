# TiempoJusto Frontend

Frontend funcional del **MVP Production V1 ONLINE**.

La aplicación no reemplaza reglas de negocio del backend. Auction, elegibilidad, billing, settlement, payout, reconnect y timers autoritativos continúan viviendo en Spring Boot/PostgreSQL.

## Alcance actual

- Golden Path integrado en `#/flow`.
- Acceso mediante OIDC Authorization Code + PKCE configurable por entorno.
- KYC mediante el proveedor configurado por backend.
- Discovery ONLINE y lectura de perfil HOST sin UUID manual.
- Proposal ONLINE.
- Listado de Auctions ONLINE abiertas, Bid y Ganar Ahora.
- Confirmación de Winner y continuidad hacia Session.
- WebRTC/TURN real con cámara obligatoria.
- FREE_ONLINE, consentimiento pagado, reconnect y resume bilateral usando estado de servidor.
- Finalización de sesión y visualización del estado de settlement.
- Wallet mediante `GET /api/v1/payments/balance`.
- Reportar y bloquear como acciones separadas.
- Admin UI separada en `#/admin`.
- Estados loading/error/vacío en las superficies principales.

La integración externa de OAuth/OIDC, KYC, Payment y TURN público sigue requiriendo el entorno staging real. El repositorio no afirma evidencia externa que todavía no haya sido ejecutada.

## Ejecutar localmente

Requisitos:

- Node.js 24+
- backend TiempoJusto en `http://localhost:8080`

```bash
cd frontend
cp .env.example .env.local
npm install
npm run dev
```

Abrir `http://localhost:5173`.

Vite reenvía `/api` y `/actuator` al backend local en `:8080`, por lo que el navegador trabaja same-origin durante desarrollo y no necesita relajar CORS solo para desarrollo.

## Build

```bash
cd frontend
npm install
npm run build
```

`npm run build` ejecuta TypeScript estricto y produce `frontend/dist` con Vite.

## Configuración

```text
VITE_TJ_API_BASE_URL=
VITE_TJ_DEV_MODE=true
VITE_TJ_OIDC_AUTHORIZATION_ENDPOINT=
VITE_TJ_OIDC_TOKEN_ENDPOINT=
VITE_TJ_OIDC_CLIENT_ID=
VITE_TJ_OIDC_SCOPE=openid profile
VITE_TJ_OIDC_REDIRECT_URI=
```

`VITE_TJ_API_BASE_URL` vacío significa same-origin. En un despliegue separado puede apuntar al origen HTTPS público de la API, acompañado por la política CORS correspondiente del backend.

`VITE_TJ_DEV_MODE=true` existe únicamente para desarrollo controlado. En ese modo puede enviarse `X-TJ-Actor-Id`. Debe estar desactivado en producción.

El login productivo usa Authorization Code + PKCE. El navegador solo recibe configuración pública del cliente OIDC. Nunca colocar client secrets, credenciales KYC, claves de pago ni secretos TURN en variables `VITE_*`, porque Vite las incorpora al bundle.

El access token de la SPA se mantiene en `sessionStorage` por defecto. El backend sigue validando issuer, audience, firma y el vínculo issuer/subject con la identidad TiempoJusto.

## Reglas de seguridad y producto

- Los countdowns usan timestamps del servidor solo para representación.
- El navegador no adjudica Auction ni extiende anti-sniping.
- Recuperar media no reinicia billing por sí solo.
- Reportar no equivale a culpabilidad y no crea automáticamente un hold financiero.
- Bloquear es una acción separada del reporte.
- El video privado no se graba ni se persiste.
- Si settlement informa que una liquidación proporcional requiere política de redondeo, la UI muestra `PENDING_ROUNDING_POLICY` y no inventa una regla CLP.

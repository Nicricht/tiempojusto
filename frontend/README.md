# TiempoJusto Frontend

Primera base funcional del **MVP Production V1 ONLINE**.

Esta aplicación no reemplaza reglas de negocio del backend. Auction, elegibilidad, billing, settlement, payout y timers autoritativos continúan viviendo en Spring Boot/PostgreSQL.

## Alcance de este corte

- Home del MVP ONLINE.
- Consulta de Auction por UUID.
- Bid real contra `POST /api/v1/auctions/{auctionId}/bids`.
- Ganar Ahora contra el endpoint real y continuidad hacia Appointment.
- Confirmación de Winner.
- Consulta y join de sesión ONLINE.
- Consentimiento de sesión pagada.
- Consulta de reconnect y aceptación bilateral de resume.
- Finalización de sesión y visualización del estado de settlement.
- Wallet mediante `GET /api/v1/payments/balance`.
- Estados loading, error y vacío para las superficies implementadas.

Todavía no significa frontend completo. Onboarding/KYC real, discovery, perfil, Proposal, OAuth/OIDC productivo, WebRTC/TURN real, Safety completo y Admin UI se cierran en sus gates del MVP Production V1.

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

## Build

```bash
cd frontend
npm install
npm run build
```

`npm run build` ejecuta primero TypeScript estricto y luego produce `frontend/dist` con Vite.

## Configuración

```text
VITE_TJ_API_BASE_URL=http://localhost:8080
VITE_TJ_DEV_MODE=true
```

`VITE_TJ_DEV_MODE=true` existe únicamente para desarrollo controlado. En ese modo aparece un panel que permite guardar localmente un UUID de actor y enviar `X-TJ-Actor-Id`, alineado con el adapter de desarrollo del backend.

En producción debe estar desactivado. La aplicación está preparada para enviar `Authorization: Bearer <token>` cuando exista un access token real, pero la integración OAuth/OIDC productiva sigue siendo un gate separado.

Nunca colocar access tokens, credenciales de proveedor, secretos KYC, claves de pago ni secretos TURN en variables `VITE_*`, porque Vite las incorpora al bundle del navegador.

## Regla de timers

Los countdowns usan timestamps entregados por el servidor únicamente para representación. El navegador no adjudica Auction, no extiende anti-sniping, no decide billable seconds y no vuelve un payout `AVAILABLE`.

## Redondeo CLP

Si settlement informa que una liquidación proporcional requiere política de redondeo, la UI lo muestra como bloqueo. No redondea por cuenta propia. La regla continúa pendiente en producto mediante `PENDING_ROUNDING_POLICY`.

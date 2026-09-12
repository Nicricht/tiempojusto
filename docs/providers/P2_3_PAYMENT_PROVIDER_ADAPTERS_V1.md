# TiempoJusto P2.3 — Payment Provider Adapters V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Materializar el primer corte de P2.3 sin fingir compatibilidad que el proveedor todavía no garantiza. El core de TiempoJusto conserva `PaymentPort` como autoridad de integración y falla cerrado cuando un proveedor no puede preservar una regla financiera V1.7.

Reglas que este corte no puede cambiar:

- una Bid válida requiere fondos respaldados/reservados;
- el settlement cobra solo el monto realmente generado;
- sesión/extensión: 80% HOST y 20% TiempoJusto sobre el monto realmente generado;
- payout HOST permanece `PENDING` y pasa a `AVAILABLE` a los 60 minutos si no existe incidencia objetiva;
- una incidencia objetiva puede llevar el payout a `HELD_FOR_REVIEW`;
- un chargeback no crea automáticamente deuda al HOST cumplidor solo por existir;
- el backend nunca almacena PAN/CVV ni datos crudos de tarjeta.

## Implementación añadida

### Finance Core

- `PaymentProviderCapabilities`: declara explícitamente operaciones soportadas por cada adapter.
- `PaymentInstrumentResolver`: entrega únicamente token opaco del proveedor, email del payer y método de pago. Su `toString()` redacta token y email.
- `ProviderPaymentStateRepository`: seam de persistencia para mapear UUID internos con referencias opacas del proveedor.
- `InMemoryProviderPaymentStateRepository`: implementación solo test/dev.
- `MercadoPagoTransport`: seam de red para autorización, captura, cancelación y refund.
- `MercadoPagoPaymentPort`: adapter candidato que implementa `PaymentPort` con estrategia fail-closed.

### Runtime Spring Boot

- `MercadoPagoHttpTransport`: transporte HTTPS candidato contra Payments API. Propaga `X-Idempotency-Key` y nunca registra/persiste el token de pago.
- `JdbcProviderPaymentStateRepository`: persistencia durable de bindings internos/proveedor.
- migración `V1_6__payment_provider_bindings.sql`.

### CI

`Payment Provider Adapters` ejecuta:

1. Finance contract tests existentes.
2. Provider adapter contract tests.
3. PostgreSQL 16 + PostGIS con schema y migraciones V1.1-V1.6.
4. Compilación del backend integrado.

## Mercado Pago — estado del candidato

Documentación oficial de Mercado Pago Chile verificada para este corte:

- Split Payments 1:1 está disponible en Chile.
- Checkout Pro / Checkout API pueden dividir automáticamente seller/marketplace.
- en Split 1:1, la comisión de Mercado Pago se descuenta primero de los fondos del seller y luego se descuenta la comisión del marketplace.
- Split 1:1 requiere OAuth por seller y requisitos KYC del proveedor.
- Payments/Checkout Bricks documenta autorización de tarjeta y captura posterior, incluyendo captura por un monto menor al autorizado.
- las APIs actuales de Orders documentan una semántica distinta: captura total y límite de captura diferente. Por eso este corte no mezcla ambas superficies de API.
- `X-Idempotency-Key` está soportado por el proveedor.

### Capacidad habilitada en el adapter candidato

| Operación TiempoJusto | Estado Mercado Pago candidate | Decisión |
|---|---|---|
| `RESERVE` | soportada por autorización manual | habilitada |
| `CAPTURE` | captura final por monto <= autorizado | habilitada |
| `RELEASE_RESERVATION` | cancelación de autorización abierta | habilitada |
| `REFUND` | soportado | habilitada |
| `ADJUST_RESERVATION` | no existe equivalencia segura para aumentar la reserva en la semántica elegida | bloqueada |
| `PAYOUT` | Split 1:1 no preserva por sí solo el hold TiempoJusto de 60 minutos ni demuestra la incidencia exacta de costos requerida | bloqueada |
| `OPEN_DISPUTE` | el chargeback/dispute real llega como evento externo del proveedor; no se debe fingir una creación remota | bloqueada |

La captura del adapter es **single-final-capture**. Si se captura menos que lo autorizado, TiempoJusto no reutiliza el saldo restante para una segunda captura.

## Por qué Mercado Pago Split 1:1 NO se activó todavía

Aunque Split 1:1 es atractivo para marketplace, su distribución automática y la incidencia publicada de la comisión del proveedor no prueban todavía estas dos invariantes de TiempoJusto:

1. HOST debe recibir exactamente el 80% del monto realmente generado según la regla de producto, salvo una política de costos que producto/legal congele explícitamente.
2. el payout debe permanecer bajo control de TiempoJusto durante el hold de 60 minutos y poder pasar a `HELD_FOR_REVIEW` por incidencia objetiva.

Activarlo sin resolver eso podría convertir una integración cómoda en una máquina de pequeñas diferencias contables. Por eso el adapter falla cerrado.

## Flow — candidato Chile

La API pública de Flow expone pagos, refunds, settlement y comercios asociados mediante `merchantId` para comercio integrador. Es un candidato relevante para Chile, pero la documentación pública revisada no demuestra de forma suficiente:

- preautorización/reserva de fondos compatible con las Bid;
- incremento seguro de una reserva existente;
- split exacto 80/20 con la regla de TiempoJusto;
- hold de payout de 60 minutos controlado por la plataforma.

Por esa razón no se agregó un adapter Flow que pretenda soportar esas capacidades. Debe validarse comercial y técnicamente con Flow antes de escribir una integración que mueva dinero real.

## Stripe

No se selecciona para el lanzamiento Chile-first porque la disponibilidad pública de Stripe Payments no presenta Chile como país de plataforma/merchant soportado para este caso.

## Bloqueadores antes de dinero real

Este merge NO activa cobros reales. Para activar producción todavía se requiere:

- cuenta/contrato comercial aprobado con el proveedor;
- credenciales sandbox/producción fuera del repositorio;
- flujo frontend de tokenización y un `PaymentInstrumentResolver` real;
- confirmar el protocolo de reemplazo de reserva para aumentar una Bid sin perder cobertura financiera;
- seleccionar/validar un proveedor de payout que respete `PENDING -> AVAILABLE >= 60m` y `HELD_FOR_REVIEW`;
- definir responsabilidad por fees, refunds y chargebacks;
- revisión legal/tributaria del modelo de marketplace y flujo de fondos en Chile;
- pruebas sandbox reales de autorización, captura parcial, cancelación, refund, webhook e idempotencia.

Hasta que esos puntos estén cerrados, perfiles actuales continúan usando `MockPaymentPort` y cualquier adapter externo permanece opt-in y fail-closed.

## Criterio de cierre de este corte

P2.3 Payment Adapter V1 queda materializado a nivel de puertos, adapter candidato, transporte HTTPS, persistencia de referencias y CI. **No equivale a PaymentPort/payout de producción activo.** El siguiente corte de P2.3 debe cerrar proveedor comercial + sandbox real + payout controlado, o seleccionar otro proveedor que satisfaga todas las capacidades V1.7.
# TiempoJusto Payment Sandbox Real V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Cerrar la infraestructura necesaria para sustituir `MockPaymentPort` por el adapter Mercado Pago en un entorno sandbox controlado, sin activar dinero real y sin degradar las invariantes financieras de TiempoJusto.

Este corte **NO declara que el sandbox externo ya haya sido probado con credenciales reales**. El repositorio no contiene credenciales y la conexión GitHub usada para construir este corte no administra secrets. La prueba externa queda habilitable cuando el operador configure credenciales sandbox fuera del repositorio.

## Implementado

### Selección explícita de PaymentPort

`TJ_PAYMENT_PROVIDER=MOCK` conserva el comportamiento de CI/dev existente.

`TJ_PAYMENT_PROVIDER=MERCADO_PAGO` deshabilita el `MockPaymentPort` y crea:

- `MercadoPagoHttpTransport`;
- `MercadoPagoPaymentPort`;
- `JdbcProviderPaymentStateRepository`;
- `EphemeralMercadoPagoInstrumentResolver`;
- `MercadoPagoPaymentQueryClient`;
- verificación HMAC de Webhooks;
- inbox/reconciliación provider -> bindings internos.

Si se selecciona Mercado Pago y faltan credenciales obligatorias, el runtime falla al iniciar. No existe fallback silencioso a Mock.

### Token de pago

El backend nunca recibe PAN/CVV como parte de este diseño.

El frontend/proveedor debe tokenizar el medio de pago. TiempoJusto recibe únicamente un token opaco mediante:

```text
POST /api/v1/sandbox/payments/mercado-pago/instrument
```

El token:

- se mantiene únicamente en memoria;
- tiene TTL corto;
- se consume una sola vez al crear una autorización;
- no se devuelve al cliente;
- no se persiste en PostgreSQL;
- no se incluye en auditoría.

Este endpoint es un seam de sandbox. La estrategia definitiva de token handoff para producción debe considerar despliegue multi-instancia antes del piloto.

### Webhooks firmados

Endpoint:

```text
POST /api/v1/webhooks/payments/mercado-pago?data.id=<provider_payment_id>&type=payment
```

Se valida `x-signature` con HMAC-SHA256 usando el manifest documentado por Mercado Pago:

```text
id:<data.id>;request-id:<x-request-id>;ts:<ts>;
```

`data.id` alfanumérico se normaliza a minúsculas antes de calcular HMAC, de acuerdo con la documentación actual del proveedor.

Una firma inválida devuelve `401` antes de insertar el evento.

### Webhook inbox V1.9

Migración:

```text
database/migrations/V1_9__payment_webhook_reconciliation.sql
```

Agrega:

- `finance.provider_webhook_event`;
- `finance.provider_reconciliation`.

El inbox conserva solo identificadores normalizados, estado de procesamiento y SHA-256 del payload. No conserva el body crudo, card token, Access Token ni webhook secret.

`provider_code + provider_event_id` es único, por lo que una entrega duplicada no genera un segundo evento lógico.

### Reconciliación

El Webhook se confirma después de validar firma y persistir el inbox. Un worker consulta luego el pago directamente al proveedor y compara con TiempoJusto.

Casos principales:

- autorización provider == reserva `RESERVED` por el mismo monto -> `MATCHED`;
- captura provider sin capture binding local todavía -> `PENDING_LOCAL_COMMIT` y retry;
- captura con monto distinto -> `MISMATCH`;
- cancelación provider + reserva local `RELEASED` -> `MATCHED`;
- refund adelantado respecto de bindings locales -> retry;
- payment provider sin binding TiempoJusto -> `UNBOUND`;
- estados externos como pending/chargeback se observan pero **no mutan automáticamente el ledger**.

Una anomalía genera `platform.audit_event`.

La reconciliación es deliberadamente read-only respecto del ledger. Un Webhook externo no puede inventar saldo, alterar el split 80/20 ni convertir un payout en AVAILABLE.

## Probe protegido

Para validar un proveedor sandbox existe un probe explícito, deshabilitado por defecto:

```text
TJ_PAYMENT_SANDBOX_PROBE_ENABLED=true
TJ_PAYMENT_SANDBOX_PROBE_KEY=<secret fuera del repo>
```

Rutas:

```text
POST /internal/payment-sandbox/full-cycle
POST /internal/payment-sandbox/reserve-release
```

El probe exige `X-TJ-Sandbox-Probe-Key` y solo trabaja con token opaco. Está pensado para staging/sandbox, no para tráfico de producto.

## Configuración

```text
TJ_PAYMENT_PROVIDER=MERCADO_PAGO
TJ_PAYMENT_MP_BASE_URL=https://api.mercadopago.com
TJ_PAYMENT_MP_ACCESS_TOKEN=<secret sandbox>
TJ_PAYMENT_MP_WEBHOOK_SECRET=<secret webhook>
TJ_PAYMENT_MP_INSTRUMENT_TTL_SECONDS=600
TJ_PAYMENT_RECONCILIATION_POLL_MS=5000
TJ_PAYMENT_RECONCILIATION_MAX_ATTEMPTS=8
```

Nunca colocar estos secrets en código, variables `VITE_*`, commits, logs o documentación con valores reales.

## CI determinista

Workflow:

```text
.github/workflows/payment-provider-integration.yml
```

Valida:

1. PostgreSQL 16/PostGIS y migraciones hasta V1.9.
2. Tests del adapter HTTP con servidor local controlado.
3. Verificación criptográfica de Webhook.
4. Reglas puras de reconciliación.
5. Compilación completa del backend.

Esto demuestra el contrato técnico sin fingir que hubo comunicación con una cuenta externa de Mercado Pago.

## Gate externo pendiente

Para marcar #22 como **completo** todavía debe ejecutarse, con una cuenta sandbox del proveedor y secrets fuera del repo:

1. tokenización sandbox desde superficie autorizada del proveedor;
2. autorización/reserva real;
3. captura real por monto permitido;
4. cancelación de autorización;
5. refund sandbox;
6. Webhook real configurado sobre HTTPS público;
7. firma real aceptada por TiempoJusto;
8. entrega duplicada/retry;
9. reconciliación provider -> binding -> ledger de la transacción de prueba;
10. evidencia de que ninguna Bid aceptada queda financieramente descubierta.

Hasta completar ese gate, `MERCADO_PAGO` continúa siendo opt-in y no debe habilitarse en producción.

## Invariantes preservadas

- no PAN/CVV persistido;
- secrets fuera del repositorio;
- Bid válida requiere cobertura financiera;
- reemplazo de reserva se hace new-first, old-release-after-commit cuando el provider no soporta ajuste;
- capture no puede exceder autorización;
- settlement conserva backend/DB como autoridad;
- split 80/20 no cambia;
- payout conserva `PENDING -> AVAILABLE >= 60m` y objective holds;
- no se inventa redondeo CLP;
- `PENDING_ROUNDING_POLICY` sigue bloqueando casos fraccionarios no definidos.

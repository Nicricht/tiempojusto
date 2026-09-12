# TiempoJusto Finance Core V1.1

Implementación ejecutable del **Ledger financiero + PaymentPort** para Java 21, ahora con adapter candidato de proveedor real y estrategia fail-closed.

## Alcance

- `PaymentPort` agnóstico del proveedor.
- `MockPaymentPort` determinista con reserva, ajuste, liberación, captura, refund, payout y dispute.
- `PaymentProviderCapabilities` para declarar capacidades reales y bloquear operaciones incompatibles.
- `MercadoPagoPaymentPort` candidato para reserva/autorización, captura final, liberación y refund.
- `MercadoPagoTransport` como seam de red.
- `PaymentInstrumentResolver` trabaja solo con token opaco del proveedor, nunca PAN/CVV.
- `ProviderPaymentStateRepository` mantiene el mapping UUID interno <-> referencia del proveedor sin guardar secretos de tarjeta.
- Idempotencia estricta en Mock y propagación de idempotencia hacia adapters externos.
- Fallos de proveedor explícitos.
- Ledger de doble entrada, append-only a nivel de API.
- Correcciones mediante transacciones compensatorias.
- Settlement de sesión/extensión 80/20 sobre monto realmente generado.
- Ticket Live 70/30.
- `PENDING -> AVAILABLE` después de 60 minutos.
- `HELD_FOR_REVIEW` ante incidencia objetiva.
- No-show HOST pre-capture: liberación 100% y HOST $0.
- Chargeback de HOST cumplidor no crea deuda automática.
- Recuperación por fraude confirmado exige referencia objetiva y se registra en Ledger.

## Ejecutar pruebas

Linux/macOS:

```bash
cd backend/finance
./run-tests.sh
```

Windows PowerShell:

```powershell
cd backend/finance
.\run-tests.ps1
```

El runner ejecuta los 22 contract tests financieros existentes y los contract tests del adapter de proveedor.

## Mercado Pago candidate

El adapter V1 habilita únicamente las capacidades que pueden representarse sin romper el contrato TiempoJusto:

- `RESERVE`: habilitado.
- `CAPTURE`: habilitado como captura final única por monto menor o igual a lo autorizado.
- `RELEASE_RESERVATION`: habilitado.
- `REFUND`: habilitado.
- `ADJUST_RESERVATION`: bloqueado salvo no-op con el mismo monto.
- `PAYOUT`: bloqueado.
- `OPEN_DISPUTE`: bloqueado como llamada remota.

Los bloqueos son intencionales. No se simula una operación que el proveedor no haya demostrado compatible con V1.7.

## Persistencia y transporte real

El runtime Spring Boot contiene:

- `JdbcProviderPaymentStateRepository`, respaldado por `V1_6__payment_provider_bindings.sql`.
- `MercadoPagoHttpTransport`, que propaga `X-Idempotency-Key` y nunca registra el token de pago.

Ninguno de estos componentes activa dinero real por sí solo. Falta un `PaymentInstrumentResolver` de producción, credenciales fuera del repositorio, pruebas sandbox reales y un proveedor/contrato de payout compatible con el hold de 60 minutos.

## Límites intencionales

Este módulo todavía NO declara un proveedor de payout de producción, NO define tarifas bancarias, NO promete tiempos de retiro bancario, NO resuelve impuestos y NO inventa una política de chargeback tardío. Tampoco activa Mercado Pago Split 1:1 porque la distribución automática y la incidencia publicada de fees no prueban por sí solas el split económico + hold de payout exigidos por TiempoJusto.

La división de CLP enteros usa `floor` para la participación HOST y asigna cualquier peso residual a plataforma. Esto sigue siendo una decisión técnica provisional porque V1.7 reconoce que la regla monetaria definitiva de redondeo debe cerrarse.
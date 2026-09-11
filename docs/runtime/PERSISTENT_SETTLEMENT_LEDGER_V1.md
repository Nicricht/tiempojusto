# TiempoJusto Persistent Settlement / Ledger V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Persistir la liquidación financiera de una sesión Online terminada sin convertir decisiones técnicas en reglas de producto nuevas.

Este corte reemplaza, para la vertical slice persistente, el uso exclusivo del ledger en memoria. El `FinanceEngine` y `MockPaymentPort` siguen existiendo como core/adapters de prueba, pero la evidencia contable de la aplicación queda en PostgreSQL.

## Reglas funcionales preservadas

- El cobro usa únicamente `billable_seconds` ya persistidos por la autoridad backend.
- El monto generado por una sesión es proporcional al tiempo pagado realmente consumido.
- Sesión y extensión usan split **80% HOST / 20% TiempoJusto** sobre el monto realmente generado.
- `PENDING_HOLD` dura como mínimo **60 minutos** antes de `AVAILABLE`.
- Un incidente objetivo puede mantener el payout pendiente.
- Un simple reporte no se convierte automáticamente en un incidente objetivo financiero.
- Un chargeback no crea deuda automática para un HOST cumplidor. Esta implementación no cambia esa regla.
- Si una sesión termina con cero segundos cobrables, no se crea movimiento de ledger y se libera la reserva completa.

## Redondeo CLP

V1.7 no congela todavía una regla de redondeo para liquidaciones proporcionales que produzcan fracciones de CLP.

Por eso este corte no usa `floor`, `ceil`, redondeo bancario ni una regla inventada.

La liquidación queda como:

`PENDING_ROUNDING_POLICY`

cuando:

1. `agreed_amount_clp * billable_seconds / full_duration_seconds` no es un CLP entero; o
2. el split 80/20 no puede representarse exactamente con CLP enteros.

Mientras esté bloqueada por esta razón no se captura dinero, no se crea transacción contable y la reserva permanece intacta.

## Persistencia V1.5

Migración:

`database/migrations/V1_5__persistent_session_settlement.sql`

Agrega:

- `finance.session_settlement`;
- `finance.payout_hold`;
- tracking `captured_amount_clp` / `released_amount_clp` de la reserva;
- `available_at` y `availability_transaction_id` en `finance.payout`;
- tipo de transacción `HOLD_RELEASE`;
- unicidad física para cuentas no-usuario;
- unicidad de settlement por sesión y payout por transacción fuente;
- guards físicos para impedir `AVAILABLE` antes del hold o con un objective hold activo.

## Double-entry

### Session settlement

Para un gross `G`:

- `PROVIDER_CLEARING / ESCROW`: DEBIT `G`
- `HOST / PENDING`: CREDIT `80% G`
- `PLATFORM / REVENUE`: CREDIT `20% G`

La transacción no puede pasar a `POSTED` si DEBIT != CREDIT.

### Hold release

Al cumplirse 60 minutos y no existir hold objetivo activo:

- `HOST / PENDING`: DEBIT monto HOST
- `HOST / AVAILABLE`: CREDIT monto HOST

El dinero cambia de bucket contable, no se envía todavía a una cuenta bancaria.

## Objective incident hold

`finance.payout_hold` exige `reason_code` y `evidence_ref`.

El servicio no interpreta automáticamente cualquier `safety.report` como evidencia objetiva. La activación del hold debe venir desde un flujo Safety/Admin explícito y auditable.

Un hold activo evita la transición a `AVAILABLE`. Al liberarlo, el payout puede avanzar si el período de 60 minutos ya terminó.

## Procesamiento asíncrono

`SessionSettlementJob` busca sesiones Online `ENDED` sin settlement persistido.

`PayoutAvailabilityJob` busca payouts cuyo `pending_until` venció y no tienen hold objetivo activo.

Los intervalos de scan son defaults técnicos configurables, no reglas de producto:

- settlement scan: 1000 ms;
- payout availability scan: 5000 ms.

## API de lectura

El contrato existente:

`GET /api/v1/payments/balance`

ahora obtiene saldos desde PostgreSQL:

- `pendingClp`;
- `availableClp`;
- `heldForReviewClp`;
- `paidOutClp`.

`heldForReviewClp` se separa de `pendingClp` para no mostrar el mismo saldo dos veces.

## Idempotencia

La captura y liberación del adapter de pago usan keys determinísticas por `sessionId`.

El ledger usa:

- `session-settlement:<sessionId>`;
- `payout-hold-release:<payoutId>`.

Si un proveedor responde y luego falla la transacción DB, un retry debe reutilizar la operación provider-idempotente y volver a intentar solo la persistencia.

## CI

Workflow:

`.github/workflows/settlement-integration.yml`

Carga PostgreSQL 16 + PostGIS con V1.0 y migraciones V1.1 a V1.5, compila el runtime y ejecuta un Golden Path que valida:

1. sesión completa de CLP 60.000;
2. gross CLP 60.000;
3. HOST CLP 48.000;
4. plataforma CLP 12.000;
5. ledger balanceado;
6. payout inicialmente `PENDING_HOLD`;
7. objective hold bloquea `AVAILABLE`;
8. al liberar el hold y vencer 60 minutos pasa a `AVAILABLE`;
9. sesión free-only produce `ZERO_BILLING` y libera reserva;
10. monto proporcional fraccional queda `PENDING_ROUNDING_POLICY` sin mover dinero.

## Límites todavía vigentes

- `MockPaymentPort` no es proveedor de pagos real y pierde su memoria si el proceso reinicia.
- No se ha seleccionado PaymentPort/payout provider productivo.
- No se implementa todavía envío bancario real de payout.
- La regla exacta de redondeo proporcional CLP sigue pendiente de producto.
- El flujo Admin/Safety que crea un objective payout hold todavía debe conectarse a `HumanReviewQueue`.
- Live Ticket 70/30 y extensiones persistentes quedan para sus vertical slices respectivas.

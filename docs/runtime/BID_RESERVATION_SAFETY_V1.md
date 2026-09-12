# TiempoJusto Bid Reservation Safety V1

Fuente funcional: Documento Maestro V1.7.

## Objetivo

Cerrar el hueco entre las reglas de Auction ya congeladas y el runtime persistente: una **Bid NORMAL aceptada nunca puede quedar sin fondos respaldados**, el backend conserva autoridad sobre monto/orden/timer y el aumento de una Bid del mismo BIDDER no libera la cobertura anterior antes de que exista cobertura nueva.

Este corte no cambia los montos, incrementos ni tiempos de V1.7. Tampoco activa dinero real.

## Endpoint materializado

```text
POST /api/v1/auctions/{auctionId}/bids
Idempotency-Key: <required>
Content-Type: application/json

{"amountClp": 50000}
```

El backend:

1. valida BIDDER activo + KYC adulto verificado;
2. bloquea la fila de Auction;
3. exige Auction abierta y antes del `effective_end_at`;
4. exige monto múltiplo de CLP 5.000 y `>= next_actionable_amount_clp`;
5. obtiene cobertura financiera antes de insertar la Bid;
6. inserta la Bid con `funds_reservation_id`;
7. deja al trigger físico secuenciar y actualizar pozo/timer;
8. emite `BID_ACCEPTED`, `POZO_UPDATED` y, si aplica, `TIMER_RESET_2M`.

El frontend no puede fijar `server_sequence`, pozo ni deadline.

## Protocolo de cobertura al aumentar la propia Bid

`BidReservationCoordinator` aplica dos caminos técnicos sin alterar la regla de negocio:

### 1. Ajuste in-place preferido

Si el `PaymentPort` soporta `ADJUST_RESERVATION`, se aumenta la reserva existente al nuevo monto. El adapter Mock usa solo el delta de fondos.

Resultado:

```text
RESERVATION A 50.000
      ↓ adjust
RESERVATION A 55.000
      ↓
BID 55.000 respaldada por A
```

### 2. Replacement fail-safe

Si el proveedor responde **explícitamente** `PAYMENT_PROVIDER_CAPABILITY_UNSUPPORTED` para `ADJUST_RESERVATION`, TiempoJusto no interpreta eso como un rechazo de fondos. Reserva primero el **nuevo monto completo** y conserva intacta la autorización anterior hasta que la nueva Bid esté confirmada en PostgreSQL.

```text
OLD RESERVATION 50.000   sigue RESERVED
            ↓
NEW RESERVATION 55.000   se crea primero
            ↓
DB COMMIT de BID 55.000
            ↓
OLD pasa a RELEASE_PENDING
            ↓
release idempotente
            ↓
OLD RELEASED
```

Esto evita el caso peligroso:

```text
liberar OLD → falla NEW → Bid previa queda descubierta
```

Ese orden está prohibido por diseño.

## Importante sobre providers sin ajuste

El fallback de replacement puede provocar una **doble autorización temporal** mientras conviven OLD + NEW. Es financieramente conservador porque nunca deja la Bid sin cobertura, pero puede reducir capacidad disponible del pagador durante unos segundos o más si el proveedor demora el release.

Por eso este protocolo permite probar de forma segura un provider que no soporte aumento de autorización, pero **no convierte automáticamente a ese provider en apto para producción**. Debe validarse en sandbox real la experiencia de autorización, release, límites y tiempos del emisor.

## Persistencia V1.8

`database/migrations/V1_8__bid_reservation_replacement.sql` agrega:

- `auction.reservation_replacement`
- estado `RELEASE_PENDING` / `RELEASED`
- idempotency key durable
- número y timestamp de intentos
- último código de error
- índice para release pendiente

La fila nueva de cobertura queda vinculada a `auction.funds_reservation`.

## Release después del commit

`BidReservationReleaseService` procesa solo reemplazos ya visibles/committed en PostgreSQL.

Características:

- `FOR UPDATE SKIP LOCKED` para evitar doble procesamiento entre workers;
- clave de release determinística por replacement;
- reintento idempotente;
- si el proveedor falla, la reserva anterior permanece `RESERVED` y el replacement queda `RELEASE_PENDING`;
- si el proceso cae después de que el provider liberó pero antes del commit local, el siguiente intento usa la misma idempotency key.

La prioridad es evitar subcobertura. Un exceso temporal de reserva es recuperable; una Bid aceptada sin respaldo no lo es.

## Anti-sniping

La regla permanece en PostgreSQL:

- si una Bid válida se confirma con `<= 2 minutos` restantes, `effective_end_at` pasa a **exactamente now + 2 minutos**;
- la aplicación detecta el cambio después del insert y emite `TIMER_RESET_2M`.

No existe timer confiado al cliente.

## Idempotencia

Una repetición con el mismo `Idempotency-Key` y mismo Auction/BIDDER/monto devuelve la Bid original.

Reutilizar esa key con payload distinto devuelve conflicto y no crea una segunda reserva.

## CI

`Bid Reservation Integration` valida:

- contratos Finance existentes;
- contratos específicos del coordinator, incluyendo fallback por capability unsupported;
- schema PostgreSQL 16/PostGIS + migraciones V1.1-V1.8;
- compilación del backend completo;
- Bid NORMAL persistente;
- `server_sequence` generado por DB;
- reserva inicial;
- aumento in-place usando solo el delta en MockPaymentPort;
- idempotency replay;
- rechazo de Bid bajo `next_actionable_amount_clp`;
- anti-sniping y `TIMER_RESET_2M`;
- outbox de `BID_ACCEPTED`.

## Lo que no resuelve este corte

- credenciales/sandbox real del proveedor de pagos;
- prueba de replacement con un emisor real;
- payout bancario real;
- fees/chargebacks de producción;
- cierre natural + ranking persistente principal/backups completo;
- liberación de reservas de participantes que ya no sean necesarios al cerrar Auction;
- política proporcional de redondeo CLP.

La política de redondeo continúa fail-closed como `PENDING_ROUNDING_POLICY`; este corte no inventa una regla nueva.

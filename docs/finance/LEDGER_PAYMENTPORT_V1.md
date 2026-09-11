# Ledger + PaymentPort Mock V1.0

## Fuente funcional

Este bloque deriva de TiempoJusto Documento Maestro V1.7. No sustituye la fuente maestra.

Reglas trasladadas a código:

- PaymentPort es una abstracción y el dominio no depende de un SDK concreto.
- Debe poder reservar/validar fondos, ajustar reserva, liberarla, capturar, hacer refund total/parcial, payout, webhooks/idempotencia y disputes.
- Una sesión/extensión distribuye 80% HOST y 20% TiempoJusto sobre el monto realmente generado.
- Live Ticket distribuye 70% HOST y 30% plataforma sobre ingreso distribuible.
- Ganancia HOST nace `PENDING` y pasa a `AVAILABLE` tras 60 minutos salvo incidencia objetiva.
- Una incidencia objetiva puede mover la operación a `HELD_FOR_REVIEW` sin borrar historial.
- No-show HOST produce recuperación total para el ganador y HOST $0.
- Chargeback no crea deuda automática de una HOST cumplidora.
- Fraude confirmado puede habilitar recuperación o balance negativo, registrado por LedgerEntry, nunca editando saldos manualmente.
- Ledger es inmutable/append-only en lógica y las correcciones se realizan con asientos compensatorios.

## Modelo de contabilización V1

### Settlement sesión/extensión

```text
DEBIT   PROVIDER_CLEARING / ESCROW     100%
CREDIT  HOST / PENDING                  80%
CREDIT  PLATFORM / REVENUE              20%
```

### Ticket Live

```text
DEBIT   PROVIDER_CLEARING / ESCROW     100%
CREDIT  HOST / PENDING                  70%
CREDIT  PLATFORM / REVENUE              30%
```

### Liberación del hold

```text
DEBIT   HOST / PENDING                 host share
CREDIT  HOST / AVAILABLE               host share
```

### Payout

```text
DEBIT   HOST / AVAILABLE               payout
CREDIT  PROVIDER_CLEARING / ESCROW     payout
```

## Decisiones técnicas, no reglas nuevas de producto

1. El mock mantiene saldos de prueba en memoria y clearing del proveedor para poder ejecutar escenarios sin dinero real.
2. Los fallos del proveedor se inyectan de forma determinista (`DECLINED`, `TIMEOUT`, etc.) para pruebas repetibles.
3. La idempotencia rechaza reutilizar una key con payload diferente.
4. El settlement captura solo el monto generado y libera la reserva sobrante. Esto reproduce el ejemplo oficial donde una Bid de $180.000 genera $90.000 y el resto se libera.
5. En montos no divisibles exactamente por el porcentaje, HOST usa floor de CLP entero y el peso residual queda temporalmente en plataforma. La política definitiva de redondeo debe cerrarse antes de producción.
6. El mock de payout es síncrono. El proveedor real deberá mapear estados asincrónicos/webhooks sin alterar la máquina económica del dominio.

## Pendientes externos que este módulo NO declara resueltos

- proveedor PaymentPort definitivo;
- aceptación del modelo comercial por el proveedor;
- tiempos y tarifas bancarias;
- impuestos/documentación tributaria;
- retención legal del Ledger;
- política exacta para disputas tardías y recuperación luego de payout;
- reglas monetarias definitivas de redondeo.

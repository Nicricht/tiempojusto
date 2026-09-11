# TiempoJusto

Repositorio técnico del proyecto **TiempoJusto**.

## Estado actual

TiempoJusto ya pasó de definición funcional a ingeniería ejecutable. El repositorio contiene el schema PostgreSQL/PostGIS V1, máquinas de estado Java 21, Ledger financiero + PaymentPort Mock, pruebas de contrato y workflows de CI.

## Estructura principal

- `database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql`  
  Loader ejecutable del schema PostgreSQL/PostGIS V1.0.
- `database/schema/parts/`  
  9 fragmentos que, en orden, reconstruyen el artefacto SQL original.
- `backend/state-machines/src/main/java/`  
  Máquinas de estado Java 21.
- `backend/state-machines/src/test/java/`  
  Suite de contract tests de máquinas de estado.
- `backend/finance/src/main/java/`  
  Ledger de doble entrada, PaymentPort agnóstico, MockPaymentPort y FinanceEngine.
- `backend/finance/src/test/java/`  
  Contract tests de reservas, capture, refund, settlement, hold, payout y chargebacks.
- `docs/finance/LEDGER_PAYMENTPORT_V1.md`  
  Trazabilidad de las reglas financieras V1.7 y decisiones técnicas explícitas.
- `.github/workflows/state-machines.yml`  
  CI de State Machines con Java 21.
- `.github/workflows/finance.yml`  
  CI del Finance Core con Java 21.

## Estado de hitos

| Hito | Estado |
|---|---|
| Producto / reglas V1.7 | ✅ definido |
| ER físico V1.0 | ✅ diseñado |
| PostgreSQL/PostGIS V1.0 | ✅ versionado |
| State Machines Java 21 | ✅ versionadas |
| Contract tests State Machines | ✅ 34/34 |
| Ledger + PaymentPort Mock | ✅ implementado |
| Contract tests Finance | ✅ 22/22 local |
| GitHub Actions | ✅ configurado |
| OpenAPI | ⏭️ siguiente |
| WebSocket | pendiente |
| UX / wireframes | pendiente |
| Integración de proveedores reales | pendiente |
| E2E / staging / piloto | pendiente |

## Ejecutar State Machines

```bash
cd backend/state-machines
bash run-tests.sh
```

En Windows:

```powershell
cd backend/state-machines
.\run-tests.ps1
```

## Ejecutar Finance Core

```bash
cd backend/finance
bash run-tests.sh
```

En Windows:

```powershell
cd backend/finance
.\run-tests.ps1
```

## Ejecutar schema PostgreSQL

Desde `database/schema/` con `psql`:

```bash
psql -d tiempojusto -f TiempoJusto_PostgreSQL_Schema_V1_0.sql
```

El loader usa `\ir` para cargar los fragmentos de `parts/` en el orden correcto.

## Próximo hito

**OpenAPI REST V1**, para convertir las reglas ya ejecutables en contratos HTTP explícitos entre frontend y backend, incluyendo idempotencia, errores, permisos y recursos financieros.

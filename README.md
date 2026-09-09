# TiempoJusto

Repositorio técnico del proyecto **TiempoJusto**.

## Estado actual

TiempoJusto ya pasó de definición funcional a ingeniería ejecutable. El repositorio contiene el primer schema PostgreSQL/PostGIS, máquinas de estado Java 21, pruebas de contrato y diagramas fuente.

## Estructura principal

- `database/schema/TiempoJusto_PostgreSQL_Schema_V1_0.sql`  
  Loader ejecutable del schema PostgreSQL/PostGIS V1.0.
- `database/schema/parts/`  
  9 fragmentos que, en orden, reconstruyen el artefacto SQL original de 1.530 líneas.
- `backend/state-machines/src/main/java/`  
  Máquinas de estado Java 21.
- `backend/state-machines/src/test/java/`  
  Suite completa de contract tests.
- `backend/state-machines/state_machine_diagrams/`  
  Diagramas Graphviz DOT de flujo central, sesiones y Safety.
- `backend/state-machines/TECHNICAL_NOTES.md`  
  Notas técnicas y decisiones de implementación.
- `backend/state-machines/TRACEABILITY.md`  
  Trazabilidad respecto de la especificación funcional V1.7.
- `.github/workflows/state-machines.yml`  
  CI para ejecutar las pruebas de máquinas de estado con Java 21.

## Estado de hitos

| Hito | Estado |
|---|---|
| Producto / reglas V1.7 | ✅ definido |
| ER físico V1.0 | ✅ diseñado |
| PostgreSQL/PostGIS V1.0 | ✅ versionado |
| State Machines Java 21 | ✅ versionadas |
| Contract tests | ✅ 34/34 en la entrega local |
| GitHub Actions | ✅ configurado |
| Ledger + PaymentPort mock | ⏭️ siguiente |
| OpenAPI | pendiente |
| WebSocket | pendiente |
| UX / wireframes | pendiente |
| Integración de proveedores | pendiente |
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

## Ejecutar schema PostgreSQL

Desde `database/schema/` con `psql`:

```bash
psql -d tiempojusto -f TiempoJusto_PostgreSQL_Schema_V1_0.sql
```

El loader usa `\ir` para cargar los fragmentos de `parts/` en el orden correcto.

## Próximo hito

**Ledger financiero + PaymentPort mock**, incluyendo reservas, capture, refund, payout, idempotencia y simulación del proveedor de pagos.

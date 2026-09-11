# Geo Core V1

Implementación Java 21 del contrato P1.3 Geo de TiempoJusto.

Componentes:

- `PublicCellService`: convierte una coordenada exacta en una celda aproximada para Discovery.
- `RoutingPort`: puerto agnóstico del proveedor.
- `MockRoutingPort`: adapter determinista exclusivo de desarrollo/tests.
- `GeoEligibilityService`: aplica `ETA <= 30 min` para elegibilidad presencial.
- `OperationalLocationPolicy`: TTL normal de GPS exacto de 24h.
- `GeoExposurePolicy`: separa proyección pública y uso operacional autorizado.

Ejecutar:

```bash
./run-tests.sh
```

El módulo no selecciona proveedor real de routing y no almacena trayectorias continuas.

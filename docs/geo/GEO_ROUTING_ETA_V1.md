# TiempoJusto P1.3 Geo / Routing / ETA V1

## Alcance

Este bloque materializa P1.3 sin reabrir reglas funcionales de TiempoJusto V1.7. La fuente maestra exige celdas públicas aproximadas, PostgreSQL/PostGIS, ETA/routing para validar el límite presencial de 30 minutos y separación estricta entre ubicación pública y ubicación operacional privada.

## Reglas provenientes de V1.7

- El mapa público usa una ubicación aproximada, no GPS exacto.
- La coordenada exacta operacional es privada y normalmente se conserva como máximo 24 horas.
- No se conserva historial continuo de rutas.
- El bidder presencial requiere ETA `<= 30 minutos`; una estimación superior no permite marcarlo `ELIGIBLE` para esa Auction.
- Arrival sigue separado de Handshake y una ubicación no inicia billing por sí sola.
- PostgreSQL/PostGIS es la base geoespacial objetivo.
- El proveedor real de mapas/routing/ETA sigue pendiente y debe validarse antes de producción.

## Decisiones técnicas V1, no reglas nuevas de producto

- `RoutingPort` desacopla el dominio de cualquier proveedor real.
- `MockRoutingPort` existe únicamente para tests/desarrollo. Usa distancia geodésica y velocidades sintéticas. No puede decidir elegibilidad de producción.
- La proyección pública de referencia usa una grilla `grid-v1` de `0.02°`. El tamaño de celda no está congelado por producto y puede cambiar tras pruebas de privacidad/UX.
- Un `RouteEstimate` técnico se considera fresco durante la ventana devuelta por el adaptador. El mock usa 5 minutos.
- La base conserva snapshots agregados de ETA con distancia, duración y referencia de proveedor, pero no una polyline ni una secuencia de puntos de ruta.

## Arquitectura

```text
cliente actualiza posición operacional
        |
        v
OperationalLocation (exacta, privada, TTL <=24h)
        |
        +--> PublicCellService --> ApproximateLocation --> Discovery/Map
        |
        +--> RoutingPort --> RouteEstimateSnapshot --> GeoEligibilityService
                                                     |
                                                     +--> ETA <=30m -> ELIGIBLE
                                                     +--> ETA >30m  -> ETA_EXCEEDS_30_MIN
```

El backend es autoridad. El cliente no puede autodeclarar un ETA ni una elegibilidad presencial.

## Base de datos

`V1_1__geo_routing_eta.sql` agrega:

- guard de retención de GPS exacto `<=24h` salvo `preservation_hold`;
- job SQL `geo.purge_expired_operational_locations()`;
- vista `geo.public_profile_location_v` que solo expone celda/centroide aproximado;
- vista `appointment.meeting_place_public_v` que oculta ciphertext y `exact_point`;
- `geo.route_estimate_snapshot` sin geometría de ruta;
- `auction.auction_participant.route_estimate_id`;
- trigger que impide `ELIGIBLE` presencial si no existe una estimación válida de `<=1800s`;
- helper PostGIS `geo.find_public_profiles_within(...)` sobre centroides aproximados.

## Privacidad

El GPS exacto no se usa en Discovery público ni en analytics generales. El snapshot de ETA no almacena el recorrido. Si existe una retención especial por Safety/disputa, debe quedar marcada mediante `preservation_hold` y ser auditable.

## Proveedor real pendiente

No se selecciona Google Maps, Mapbox, HERE, TomTom u otro proveedor en este hito. La selección real debe validar al menos cobertura, precisión ETA, límites de uso, privacidad, costos y compatibilidad con la regla funcional `ETA <=30m`.

## Pruebas de contrato

El módulo `backend/geo` cubre límites geográficos, proyección pública, no exposición del punto exacto, autorización operacional, TTL 24h, preservation hold, routing mock, borde exacto de 30 minutos, rechazo sobre 30 minutos y ausencia de geometría de ruta en `RouteEstimate`.

## Siguiente hito

P1.4 WebRTC: lifecycle de sala, TURN, heartbeat de media, validez de cámara y eventos de reconexión para Online/Live.

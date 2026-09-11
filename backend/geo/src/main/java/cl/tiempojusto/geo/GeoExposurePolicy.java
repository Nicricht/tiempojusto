package cl.tiempojusto.geo;

/** Separates public map data from exact operational location data. */
public final class GeoExposurePolicy {
    private final PublicCellService cellService;

    public GeoExposurePolicy(PublicCellService cellService) {
        this.cellService = cellService;
    }

    public PublicCell publicProjection(GeoPoint exact) {
        return cellService.project(exact);
    }

    public GeoPoint exactForOperationalUse(GeoPoint exact, boolean authorizedOperationalContext) {
        if (!authorizedOperationalContext) {
            throw new SecurityException("exact location is restricted to authorized operational context");
        }
        return exact;
    }
}

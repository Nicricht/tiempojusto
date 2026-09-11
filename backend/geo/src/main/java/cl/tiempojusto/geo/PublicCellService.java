package cl.tiempojusto.geo;

import java.util.Locale;

/**
 * V1 technical default for public map projection.
 * This is intentionally provider-neutral and must never be used as an exact operational point.
 */
public final class PublicCellService {
    /** Approx. 0.02 degrees. Product V1.7 does not freeze a cell size; this is an engineering default. */
    public static final double DEFAULT_GRID_DEGREES = 0.02d;
    public static final int DEFAULT_PRECISION_LEVEL = 2;

    private final double gridDegrees;
    private final int precisionLevel;

    public PublicCellService() {
        this(DEFAULT_GRID_DEGREES, DEFAULT_PRECISION_LEVEL);
    }

    public PublicCellService(double gridDegrees, int precisionLevel) {
        if (!Double.isFinite(gridDegrees) || gridDegrees <= 0 || gridDegrees > 5) {
            throw new IllegalArgumentException("gridDegrees out of range");
        }
        if (precisionLevel < 0) throw new IllegalArgumentException("precisionLevel must be >= 0");
        this.gridDegrees = gridDegrees;
        this.precisionLevel = precisionLevel;
    }

    public PublicCell project(GeoPoint exact) {
        double latBase = Math.floor((exact.latitude() + 90.0) / gridDegrees) * gridDegrees - 90.0;
        double lonBase = Math.floor((exact.longitude() + 180.0) / gridDegrees) * gridDegrees - 180.0;
        double latCenter = clamp(latBase + gridDegrees / 2.0, -90.0, 90.0);
        double lonCenter = normalizeLon(lonBase + gridDegrees / 2.0);
        long latIndex = (long) Math.floor((exact.latitude() + 90.0) / gridDegrees);
        long lonIndex = (long) Math.floor((exact.longitude() + 180.0) / gridDegrees);
        String code = String.format(Locale.ROOT, "grid-v1:%d:%d:p%d", latIndex, lonIndex, precisionLevel);
        return new PublicCell(code, new GeoPoint(latCenter, lonCenter), precisionLevel);
    }

    public double gridDegrees() { return gridDegrees; }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeLon(double lon) {
        if (lon > 180.0) return lon - 360.0;
        if (lon < -180.0) return lon + 360.0;
        return lon;
    }
}

package cl.tiempojusto.geo;

public record PublicCell(String cellCode, GeoPoint centroid, int precisionLevel) {
    public PublicCell {
        if (cellCode == null || cellCode.isBlank()) throw new IllegalArgumentException("cellCode required");
        if (centroid == null) throw new IllegalArgumentException("centroid required");
        if (precisionLevel < 0) throw new IllegalArgumentException("precisionLevel must be >= 0");
    }
}

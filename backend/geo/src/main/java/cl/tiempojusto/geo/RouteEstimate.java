package cl.tiempojusto.geo;

import java.time.Instant;

public record RouteEstimate(
        long distanceMeters,
        int durationSeconds,
        TravelMode travelMode,
        String provider,
        String providerReference,
        Instant estimatedAt,
        Instant validUntil) {

    public RouteEstimate {
        if (distanceMeters < 0) throw new IllegalArgumentException("distanceMeters must be >= 0");
        if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must be >= 0");
        if (travelMode == null) throw new IllegalArgumentException("travelMode required");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");
        if (estimatedAt == null || validUntil == null || !validUntil.isAfter(estimatedAt)) {
            throw new IllegalArgumentException("valid estimate window required");
        }
    }

    public boolean isEligibleForInPerson() {
        return durationSeconds <= 30 * 60;
    }
}

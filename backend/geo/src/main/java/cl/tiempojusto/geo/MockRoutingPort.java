package cl.tiempojusto.geo;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Deterministic test adapter. It is not a road-routing engine and must not be used for production eligibility.
 */
public final class MockRoutingPort implements RoutingPort {
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    @Override
    public RouteEstimate estimate(GeoPoint origin, GeoPoint destination, TravelMode mode, Instant now) {
        if (origin == null || destination == null || mode == null || now == null) {
            throw new IllegalArgumentException("origin, destination, mode and now are required");
        }
        long directDistance = Math.round(haversineMeters(origin, destination));
        double detourFactor = switch (mode) {
            case DRIVING -> 1.25;
            case WALKING -> 1.15;
            case CYCLING -> 1.20;
            case TRANSIT -> 1.35;
            case UNKNOWN -> 1.30;
        };
        double metersPerSecond = switch (mode) {
            case DRIVING -> 8.33;
            case WALKING -> 1.35;
            case CYCLING -> 4.17;
            case TRANSIT -> 6.94;
            case UNKNOWN -> 5.0;
        };
        long routeDistance = Math.round(directDistance * detourFactor);
        int duration = (int) Math.ceil(routeDistance / metersPerSecond);
        return new RouteEstimate(
                routeDistance,
                duration,
                mode,
                "mock-routing-v1",
                "mock-" + UUID.nameUUIDFromBytes((origin.toString()+destination+mode+now.getEpochSecond()).getBytes()),
                now,
                now.plus(Duration.ofMinutes(5)));
    }

    static double haversineMeters(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2.0 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}

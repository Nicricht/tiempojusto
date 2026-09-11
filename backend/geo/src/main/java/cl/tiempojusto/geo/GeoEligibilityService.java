package cl.tiempojusto.geo;

import java.time.Instant;

public final class GeoEligibilityService {
    public static final int MAX_IN_PERSON_ETA_SECONDS = 30 * 60;

    private final RoutingPort routingPort;

    public GeoEligibilityService(RoutingPort routingPort) {
        if (routingPort == null) throw new IllegalArgumentException("routingPort required");
        this.routingPort = routingPort;
    }

    public Eligibility evaluateInPerson(GeoPoint bidder, GeoPoint destination, TravelMode mode, Instant now) {
        RouteEstimate estimate = routingPort.estimate(bidder, destination, mode, now);
        boolean eligible = estimate.durationSeconds() <= MAX_IN_PERSON_ETA_SECONDS;
        return new Eligibility(eligible, estimate, eligible ? "ELIGIBLE" : "ETA_EXCEEDS_30_MIN");
    }

    public record Eligibility(boolean eligible, RouteEstimate estimate, String reasonCode) {
        public Eligibility {
            if (estimate == null) throw new IllegalArgumentException("estimate required");
            if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode required");
        }
    }
}

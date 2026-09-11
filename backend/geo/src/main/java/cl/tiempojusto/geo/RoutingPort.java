package cl.tiempojusto.geo;

import java.time.Instant;

/** Provider-neutral routing abstraction. Real provider selection remains external/pending. */
public interface RoutingPort {
    RouteEstimate estimate(GeoPoint origin, GeoPoint destination, TravelMode mode, Instant now);
}

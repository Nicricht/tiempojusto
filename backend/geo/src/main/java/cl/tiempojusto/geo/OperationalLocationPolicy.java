package cl.tiempojusto.geo;

import java.time.Duration;
import java.time.Instant;

public final class OperationalLocationPolicy {
    public static final Duration DEFAULT_EXACT_GPS_RETENTION = Duration.ofHours(24);

    private OperationalLocationPolicy() {}

    public static Instant purgeAfter(Instant capturedAt) {
        if (capturedAt == null) throw new IllegalArgumentException("capturedAt required");
        return capturedAt.plus(DEFAULT_EXACT_GPS_RETENTION);
    }

    public static boolean isDefaultRetentionValid(Instant capturedAt, Instant purgeAfter, boolean preservationHold) {
        if (capturedAt == null || purgeAfter == null || !purgeAfter.isAfter(capturedAt)) return false;
        return preservationHold || !purgeAfter.isAfter(capturedAt.plus(DEFAULT_EXACT_GPS_RETENTION));
    }
}

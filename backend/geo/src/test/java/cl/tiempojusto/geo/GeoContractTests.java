package cl.tiempojusto.geo;

import java.time.Instant;

public final class GeoContractTests {
    private static int passed = 0;

    public static void main(String[] args) {
        run("geo point validates bounds", GeoContractTests::pointBounds);
        run("public cell is deterministic", GeoContractTests::cellDeterministic);
        run("public cell does not equal exact coordinate", GeoContractTests::publicNotExact);
        run("exact operational access requires authorization", GeoContractTests::exactRequiresAuthorization);
        run("GPS default purge is exactly 24h", GeoContractTests::gpsRetention);
        run("GPS >24h invalid without hold", GeoContractTests::gpsOverRetentionRejected);
        run("GPS >24h allowed with preservation hold", GeoContractTests::gpsHoldAllowed);
        run("mock route estimate is deterministic enough", GeoContractTests::routeEstimate);
        run("ETA 30m is eligible", GeoContractTests::etaExactly30Eligible);
        run("ETA over 30m is ineligible", GeoContractTests::etaOver30Rejected);
        run("route estimate stores no polyline", GeoContractTests::noPolylineField);
        run("walking route longer than driving for same points", GeoContractTests::walkingSlower);
        System.out.println("PASS: " + passed + "/12 Geo contract tests");
    }

    private static void run(String name, Runnable test) {
        try { test.run(); passed++; }
        catch (Throwable t) { throw new AssertionError("FAIL: " + name + " -> " + t.getMessage(), t); }
    }

    private static void pointBounds() {
        expectThrows(() -> new GeoPoint(91, 0));
        expectThrows(() -> new GeoPoint(0, -181));
        new GeoPoint(-33.45, -70.66);
    }

    private static void cellDeterministic() {
        PublicCellService s = new PublicCellService();
        GeoPoint p = new GeoPoint(-33.45, -70.66);
        eq(s.project(p).cellCode(), s.project(p).cellCode());
    }

    private static void publicNotExact() {
        PublicCell c = new PublicCellService().project(new GeoPoint(-33.451234, -70.661234));
        if (Math.abs(c.centroid().latitude() + 33.451234) < 1e-8 && Math.abs(c.centroid().longitude() + 70.661234) < 1e-8) {
            throw new AssertionError("public projection leaked exact point");
        }
    }

    private static void exactRequiresAuthorization() {
        GeoExposurePolicy p = new GeoExposurePolicy(new PublicCellService());
        expectThrows(() -> p.exactForOperationalUse(new GeoPoint(0,0), false));
        p.exactForOperationalUse(new GeoPoint(0,0), true);
    }

    private static void gpsRetention() {
        Instant t = Instant.parse("2026-09-11T12:00:00Z");
        eq(t.plusSeconds(86400), OperationalLocationPolicy.purgeAfter(t));
    }

    private static void gpsOverRetentionRejected() {
        Instant t = Instant.parse("2026-09-11T12:00:00Z");
        if (OperationalLocationPolicy.isDefaultRetentionValid(t, t.plusSeconds(90000), false)) {
            throw new AssertionError("over-retention accepted");
        }
    }

    private static void gpsHoldAllowed() {
        Instant t = Instant.parse("2026-09-11T12:00:00Z");
        if (!OperationalLocationPolicy.isDefaultRetentionValid(t, t.plusSeconds(90000), true)) {
            throw new AssertionError("preservation hold rejected");
        }
    }

    private static void routeEstimate() {
        RoutingPort r = new MockRoutingPort();
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        RouteEstimate e = r.estimate(new GeoPoint(-33.45,-70.66), new GeoPoint(-33.44,-70.65), TravelMode.DRIVING, now);
        if (e.distanceMeters() <= 0 || e.durationSeconds() <= 0 || !e.validUntil().isAfter(now)) throw new AssertionError("invalid estimate");
    }

    private static void etaExactly30Eligible() {
        RoutingPort r = (o,d,m,n) -> new RouteEstimate(10_000, 1800, m, "test", "ref", n, n.plusSeconds(60));
        var result = new GeoEligibilityService(r).evaluateInPerson(new GeoPoint(0,0), new GeoPoint(0,1), TravelMode.DRIVING, Instant.now());
        if (!result.eligible()) throw new AssertionError("30m should be eligible");
    }

    private static void etaOver30Rejected() {
        RoutingPort r = (o,d,m,n) -> new RouteEstimate(10_000, 1801, m, "test", "ref", n, n.plusSeconds(60));
        var result = new GeoEligibilityService(r).evaluateInPerson(new GeoPoint(0,0), new GeoPoint(0,1), TravelMode.DRIVING, Instant.now());
        if (result.eligible() || !"ETA_EXCEEDS_30_MIN".equals(result.reasonCode())) throw new AssertionError("31m+ should be rejected");
    }

    private static void noPolylineField() {
        for (var c : RouteEstimate.class.getRecordComponents()) {
            if (c.getName().toLowerCase().contains("polyline") || c.getName().toLowerCase().contains("geometry")) {
                throw new AssertionError("route geometry must not be retained by default");
            }
        }
    }

    private static void walkingSlower() {
        MockRoutingPort r = new MockRoutingPort();
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        GeoPoint a = new GeoPoint(-33.45,-70.66), b = new GeoPoint(-33.44,-70.65);
        int drive = r.estimate(a,b,TravelMode.DRIVING,now).durationSeconds();
        int walk = r.estimate(a,b,TravelMode.WALKING,now).durationSeconds();
        if (walk <= drive) throw new AssertionError("walking mock should be slower");
    }

    private static void eq(Object a, Object b) {
        if (!java.util.Objects.equals(a,b)) throw new AssertionError("expected " + a + " == " + b);
    }

    private static void expectThrows(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { return; }
        throw new AssertionError("expected exception");
    }
}

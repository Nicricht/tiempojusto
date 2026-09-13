package cl.tiempojusto.media;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CoturnWebRtcPortTests {
    private static final Instant T0 = Instant.parse("2026-09-13T12:00:00Z");

    public static void main(String[] args) {
        shortLivedCredentials();
        credentialsAreScopedPerUser();
        privateRecordingStillForbidden();
        ttlCannotExceedFifteenMinutes();
        System.out.println("PASS: 4/4 CoturnWebRtcPort tests");
    }

    private static void shortLivedCredentials() {
        var port = port();
        var room = port.createRoom(RoomKind.ONLINE_PRIVATE, UUID.randomUUID(), false, T0);
        var user = UUID.randomUUID();
        var credentials = port.issueTurnCredentials(room, user, T0);
        eq(T0.plusSeconds(600), credentials.expiresAt());
        ok(credentials.username().startsWith(Long.toString(credentials.expiresAt().getEpochSecond()) + ":"));
        ok(credentials.username().endsWith(user.toString()));
        ok(!credentials.credential().isBlank());
        eq(List.of("turn:127.0.0.1:3478?transport=udp"), credentials.urls());
    }

    private static void credentialsAreScopedPerUser() {
        var port = port();
        var room = port.createRoom(RoomKind.ONLINE_PRIVATE, UUID.randomUUID(), false, T0);
        var first = port.issueTurnCredentials(room, UUID.randomUUID(), T0);
        var second = port.issueTurnCredentials(room, UUID.randomUUID(), T0);
        ok(!first.username().equals(second.username()));
        ok(!first.credential().equals(second.credential()));
    }

    private static void privateRecordingStillForbidden() {
        var port = port();
        try {
            port.createRoom(RoomKind.ONLINE_PRIVATE, UUID.randomUUID(), true, T0);
            throw new AssertionError("expected private recording guard");
        } catch (IllegalArgumentException expected) {
            ok(expected.getMessage().contains("private Online rooms"));
        }
    }

    private static void ttlCannotExceedFifteenMinutes() {
        try {
            new CoturnWebRtcPort(List.of("turn:127.0.0.1:3478"), "unit-test-key", Duration.ofMinutes(16));
            throw new AssertionError("expected ttl guard");
        } catch (IllegalArgumentException expected) {
            ok(expected.getMessage().contains("15 minutes"));
        }
    }

    private static CoturnWebRtcPort port() {
        return new CoturnWebRtcPort(
                List.of("turn:127.0.0.1:3478?transport=udp"),
                "unit-test-key",
                Duration.ofMinutes(10));
    }

    private static void ok(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}

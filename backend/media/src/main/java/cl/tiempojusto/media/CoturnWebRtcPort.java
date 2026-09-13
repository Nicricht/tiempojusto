package cl.tiempojusto.media;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * WebRtcPort backed by the standard coturn TURN REST shared-secret mechanism.
 *
 * Coturn does not own application rooms, so createRoom creates an opaque logical
 * room reference while TURN credentials are issued with a short TTL. The shared
 * secret never leaves the server. Private ONLINE rooms can never enable persistent
 * recording.
 */
public final class CoturnWebRtcPort implements WebRtcPort {
    private static final String PROVIDER = "COTURN";
    private static final Duration MAX_CREDENTIAL_TTL = Duration.ofMinutes(15);

    private final List<String> turnUrls;
    private final byte[] sharedSecret;
    private final Duration credentialTtl;

    public CoturnWebRtcPort(List<String> turnUrls, String sharedSecret, Duration credentialTtl) {
        if (turnUrls == null || turnUrls.isEmpty()) {
            throw new IllegalArgumentException("TURN urls required");
        }
        this.turnUrls = turnUrls.stream()
                .map(String::trim)
                .filter(url -> !url.isBlank())
                .toList();
        if (this.turnUrls.isEmpty() || this.turnUrls.stream().anyMatch(url -> !(url.startsWith("turn:") || url.startsWith("turns:")))) {
            throw new IllegalArgumentException("TURN urls must use turn: or turns:");
        }
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalArgumentException("TURN shared secret required");
        }
        this.sharedSecret = sharedSecret.getBytes(StandardCharsets.UTF_8);
        this.credentialTtl = Objects.requireNonNull(credentialTtl, "credentialTtl required");
        if (credentialTtl.isZero() || credentialTtl.isNegative() || credentialTtl.compareTo(MAX_CREDENTIAL_TTL) > 0) {
            throw new IllegalArgumentException("TURN credential TTL must be > 0 and <= 15 minutes");
        }
    }

    @Override
    public RoomHandle createRoom(RoomKind kind, UUID aggregateId, boolean persistentRecordingEnabled, Instant now) {
        Objects.requireNonNull(kind, "kind required");
        Objects.requireNonNull(aggregateId, "aggregateId required");
        Objects.requireNonNull(now, "now required");
        return new RoomHandle(PROVIDER, "tj-" + kind.name().toLowerCase() + "-" + aggregateId, kind, persistentRecordingEnabled);
    }

    @Override
    public TurnCredentials issueTurnCredentials(RoomHandle room, UUID userId, Instant now) {
        Objects.requireNonNull(room, "room required");
        Objects.requireNonNull(userId, "userId required");
        Objects.requireNonNull(now, "now required");
        if (room.kind() == RoomKind.ONLINE_PRIVATE && room.persistentRecordingEnabled()) {
            throw MediaException.of("PRIVATE_RECORDING_FORBIDDEN", "Private Online rooms cannot enable persistent recording");
        }

        Instant expiresAt = now.plus(credentialTtl);
        String username = expiresAt.getEpochSecond() + ":" + userId;
        String credential = hmacSha1Base64(username);
        return new TurnCredentials(turnUrls, username, credential, expiresAt);
    }

    @Override
    public void closeRoom(RoomHandle room, Instant now) {
        Objects.requireNonNull(room, "room required");
        Objects.requireNonNull(now, "now required");
        // Coturn relays are allocation-based. Expiring short-lived credentials and
        // closing the browser PeerConnection are sufficient; there is no provider room to delete.
    }

    @Override
    public boolean healthy() {
        // Configuration health only. Network reachability is proven by the browser
        // relay-only integration test and external staging smoke, not by leaking a
        // TURN secret into a generic health endpoint.
        return !turnUrls.isEmpty() && sharedSecret.length > 0;
    }

    private String hmacSha1Base64(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sharedSecret, "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to issue TURN credential", exception);
        }
    }
}

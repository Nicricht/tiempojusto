package cl.tiempojusto.media;

import java.time.Instant;
import java.util.UUID;

public record ParticipantMediaState(UUID userId, ParticipantRole role, Instant joinedAt, Instant lastValidMediaAt,
                                    boolean cameraValid, boolean mediaFlowing, boolean audioMuted) {
    public ParticipantMediaState {
        if (userId == null || role == null) throw new IllegalArgumentException("user and role required");
    }

    public static ParticipantMediaState absent(UUID userId, ParticipantRole role) {
        return new ParticipantMediaState(userId, role, null, null, false, false, false);
    }

    public ParticipantMediaState join(Instant now, boolean cameraValid, boolean mediaFlowing) {
        Instant valid = cameraValid && mediaFlowing ? now : lastValidMediaAt;
        return new ParticipantMediaState(userId, role, now, valid, cameraValid, mediaFlowing, audioMuted);
    }

    public ParticipantMediaState heartbeat(Instant now, boolean cameraValid, boolean mediaFlowing, boolean audioMuted) {
        Instant valid = cameraValid && mediaFlowing ? now : lastValidMediaAt;
        return new ParticipantMediaState(userId, role, joinedAt, valid, cameraValid, mediaFlowing, audioMuted);
    }

    public boolean joined() { return joinedAt != null; }
    public boolean validVideo() { return joined() && cameraValid && mediaFlowing; }
}

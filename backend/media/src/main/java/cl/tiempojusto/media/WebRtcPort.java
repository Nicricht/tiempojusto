package cl.tiempojusto.media;

import java.time.Instant;
import java.util.UUID;

public interface WebRtcPort {
    RoomHandle createRoom(RoomKind kind, UUID aggregateId, boolean persistentRecordingEnabled, Instant now);
    TurnCredentials issueTurnCredentials(RoomHandle room, UUID userId, Instant now);
    void closeRoom(RoomHandle room, Instant now);
    boolean healthy();
}

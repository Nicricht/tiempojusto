package cl.tiempojusto.media;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MockWebRtcPort implements WebRtcPort {
    private final Set<String> openRooms = new HashSet<>();
    private boolean healthy = true;

    @Override
    public RoomHandle createRoom(RoomKind kind, UUID aggregateId, boolean persistentRecordingEnabled, Instant now) {
        if (!healthy) throw MediaException.of("WEBRTC_PROVIDER_UNAVAILABLE", "Mock WebRTC provider is unavailable");
        RoomHandle room = new RoomHandle("MOCK", "mock-" + kind.name().toLowerCase() + "-" + aggregateId, kind, persistentRecordingEnabled);
        openRooms.add(room.providerRoomRef());
        return room;
    }

    @Override
    public TurnCredentials issueTurnCredentials(RoomHandle room, UUID userId, Instant now) {
        if (!healthy) throw MediaException.of("TURN_UNAVAILABLE", "Mock TURN is unavailable");
        if (!openRooms.contains(room.providerRoomRef())) throw MediaException.of("ROOM_NOT_OPEN", "Room is not open");
        Instant expiry = now.plus(Duration.ofMinutes(10));
        String username = userId + ":" + expiry.getEpochSecond();
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString((room.providerRoomRef()+":"+username).getBytes(StandardCharsets.UTF_8));
        return new TurnCredentials(List.of("turn:mock.invalid:3478?transport=udp", "turns:mock.invalid:5349?transport=tcp"), username, credential, expiry);
    }

    @Override
    public void closeRoom(RoomHandle room, Instant now) { openRooms.remove(room.providerRoomRef()); }

    @Override
    public boolean healthy() { return healthy; }

    public void setHealthy(boolean healthy) { this.healthy = healthy; }
    public boolean isOpen(RoomHandle room) { return openRooms.contains(room.providerRoomRef()); }
}

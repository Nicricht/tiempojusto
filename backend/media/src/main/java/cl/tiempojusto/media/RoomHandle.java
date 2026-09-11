package cl.tiempojusto.media;

public record RoomHandle(String provider, String providerRoomRef, RoomKind kind, boolean persistentRecordingEnabled) {
    public RoomHandle {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");
        if (providerRoomRef == null || providerRoomRef.isBlank()) throw new IllegalArgumentException("providerRoomRef required");
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (kind == RoomKind.ONLINE_PRIVATE && persistentRecordingEnabled) {
            throw new IllegalArgumentException("private Online rooms cannot enable persistent recording");
        }
    }
}

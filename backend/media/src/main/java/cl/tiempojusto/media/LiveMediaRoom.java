package cl.tiempojusto.media;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class LiveMediaRoom {
    public static final Duration RECONNECT_WINDOW = Duration.ofMinutes(2);
    private final UUID liveSessionId;
    private final RoomHandle room;
    private final MediaRoomState state;
    private final Instant startedAt;
    private final Instant mediaLostAt;
    private final Instant reconnectDeadline;
    private final boolean auctionContinues;

    private LiveMediaRoom(UUID liveSessionId, RoomHandle room, MediaRoomState state, Instant startedAt, Instant mediaLostAt, Instant reconnectDeadline) {
        this.liveSessionId=liveSessionId; this.room=room; this.state=state; this.startedAt=startedAt; this.mediaLostAt=mediaLostAt; this.reconnectDeadline=reconnectDeadline; this.auctionContinues=true;
    }

    public static LiveMediaRoom start(UUID liveSessionId, long auctionRemainingSeconds, WebRtcPort port, Instant now) {
        if (auctionRemainingSeconds < 300) throw MediaException.of("LIVE_TOO_LATE", "Live requires at least 5 minutes remaining in Auction");
        RoomHandle handle=port.createRoom(RoomKind.LIVE_AUCTION,liveSessionId,false,now);
        return new LiveMediaRoom(liveSessionId,handle,MediaRoomState.LIVE,now,null,null);
    }

    public LiveMediaRoom confirmTransmissionLoss(Instant now) {
        if (state != MediaRoomState.LIVE) throw MediaException.of("MEDIA_BAD_STATE", "Live loss requires LIVE state");
        return new LiveMediaRoom(liveSessionId,room,MediaRoomState.RECONNECTING,startedAt,now,now.plus(RECONNECT_WINDOW));
    }

    public LiveMediaRoom recover(Instant now) {
        if (state != MediaRoomState.RECONNECTING) throw MediaException.of("MEDIA_BAD_STATE", "Live recovery requires RECONNECTING");
        if (now.isAfter(reconnectDeadline)) throw MediaException.of("LIVE_RECONNECT_TIMEOUT", "Live reconnect window expired");
        return new LiveMediaRoom(liveSessionId,room,MediaRoomState.LIVE,startedAt,null,null);
    }

    public LiveMediaRoom reconnectTimeout(Instant now) {
        if (state != MediaRoomState.RECONNECTING) throw MediaException.of("MEDIA_BAD_STATE", "Live reconnect timeout requires RECONNECTING");
        if (now.isBefore(reconnectDeadline)) throw MediaException.of("LIVE_RECONNECT_NOT_DUE", "Live reconnect deadline has not expired");
        return new LiveMediaRoom(liveSessionId,room,MediaRoomState.ENDED,startedAt,mediaLostAt,reconnectDeadline);
    }

    public LiveMediaRoom endLegitimately() { return new LiveMediaRoom(liveSessionId,room,MediaRoomState.ENDED,startedAt,mediaLostAt,reconnectDeadline); }
    public UUID liveSessionId(){return liveSessionId;} public RoomHandle room(){return room;} public MediaRoomState state(){return state;}
    public Instant startedAt(){return startedAt;} public Instant mediaLostAt(){return mediaLostAt;} public Instant reconnectDeadline(){return reconnectDeadline;}
    public boolean auctionContinues(){return auctionContinues;} public boolean persistentRecordingEnabled(){return room.persistentRecordingEnabled();}
}

package cl.tiempojusto.media;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class OnlineMediaRoom {
    public static final Duration JOIN_WINDOW = Duration.ofMinutes(3);
    public static final Duration MICRO_CUT_TOLERANCE = Duration.ofSeconds(5);
    public static final Duration RECONNECT_WINDOW = Duration.ofMinutes(2);

    private final UUID appointmentId;
    private final RoomHandle room;
    private final Instant createdAt;
    private final Instant joinDeadline;
    private final ParticipantMediaState host;
    private final ParticipantMediaState bidder;
    private final MediaRoomState state;
    private final Instant reconnectDeadline;
    private final Instant pauseBillingFrom;

    private OnlineMediaRoom(UUID appointmentId, RoomHandle room, Instant createdAt, Instant joinDeadline,
                            ParticipantMediaState host, ParticipantMediaState bidder, MediaRoomState state,
                            Instant reconnectDeadline, Instant pauseBillingFrom) {
        this.appointmentId = appointmentId; this.room = room; this.createdAt = createdAt; this.joinDeadline = joinDeadline;
        this.host = host; this.bidder = bidder; this.state = state; this.reconnectDeadline = reconnectDeadline; this.pauseBillingFrom = pauseBillingFrom;
    }

    public static OnlineMediaRoom create(UUID appointmentId, UUID hostUserId, UUID bidderUserId, WebRtcPort port, Instant now) {
        RoomHandle handle = port.createRoom(RoomKind.ONLINE_PRIVATE, appointmentId, false, now);
        return new OnlineMediaRoom(appointmentId, handle, now, now.plus(JOIN_WINDOW),
                ParticipantMediaState.absent(hostUserId, ParticipantRole.HOST),
                ParticipantMediaState.absent(bidderUserId, ParticipantRole.BIDDER),
                MediaRoomState.JOIN_WINDOW, null, null);
    }

    public OnlineMediaRoom join(ParticipantRole role, Instant now, boolean cameraValid, boolean mediaFlowing) {
        if (state != MediaRoomState.JOIN_WINDOW) throw MediaException.of("MEDIA_BAD_STATE", "Join is only allowed during JOIN_WINDOW");
        if (now.isAfter(joinDeadline)) throw MediaException.of("ONLINE_JOIN_TIMEOUT", "The simultaneous 3-minute join window has expired");
        ParticipantMediaState h = host, b = bidder;
        if (role == ParticipantRole.HOST) h = host.join(now, cameraValid, mediaFlowing);
        else if (role == ParticipantRole.BIDDER) b = bidder.join(now, cameraValid, mediaFlowing);
        else throw MediaException.of("ONLINE_ROLE_INVALID", "Spectator cannot join private Online room");
        MediaRoomState next = h.validVideo() && b.validVideo() ? MediaRoomState.READY_FOR_FREE : MediaRoomState.JOIN_WINDOW;
        return copy(h,b,next,reconnectDeadline,pauseBillingFrom);
    }

    public OnlineMediaRoom heartbeat(ParticipantRole role, Instant now, boolean cameraValid, boolean mediaFlowing, boolean audioMuted) {
        if (state == MediaRoomState.ENDED) return this;
        ParticipantMediaState h = host, b = bidder;
        if (role == ParticipantRole.HOST) h = host.heartbeat(now,cameraValid,mediaFlowing,audioMuted);
        else if (role == ParticipantRole.BIDDER) b = bidder.heartbeat(now,cameraValid,mediaFlowing,audioMuted);
        else throw MediaException.of("ONLINE_ROLE_INVALID", "Spectator cannot heartbeat in private Online room");
        return copy(h,b,state,reconnectDeadline,pauseBillingFrom);
    }

    public OnlineMediaRoom markPaidActive(Instant now) {
        if (!(state == MediaRoomState.READY_FOR_FREE || state == MediaRoomState.RECOVERED_AWAITING_BILATERAL_RESUME))
            throw MediaException.of("MEDIA_BAD_STATE", "Paid activation requires valid media readiness");
        if (!(host.validVideo() && bidder.validVideo())) throw MediaException.of("ONLINE_CAMERA_REQUIRED", "Valid camera/video is required for both parties");
        return copy(host,bidder,MediaRoomState.PAID_ACTIVE,null,null);
    }

    public OnlineMediaRoom evaluatePaidMedia(Instant now) {
        if (state != MediaRoomState.PAID_ACTIVE) return this;
        Instant lastCommon = lastCommonValidMediaAt();
        if (lastCommon == null || !now.isAfter(lastCommon.plus(MICRO_CUT_TOLERANCE))) return this;
        return copy(host,bidder,MediaRoomState.RECONNECTING,now.plus(RECONNECT_WINDOW),lastCommon);
    }

    public OnlineMediaRoom markRecovered(Instant now) {
        if (state != MediaRoomState.RECONNECTING) throw MediaException.of("MEDIA_BAD_STATE", "Recovery requires RECONNECTING");
        if (now.isAfter(reconnectDeadline)) throw MediaException.of("ONLINE_RECONNECT_TIMEOUT", "The 2-minute reconnect window has expired");
        if (!(host.validVideo() && bidder.validVideo())) throw MediaException.of("ONLINE_MEDIA_NOT_RECOVERED", "Both participants need valid camera/video");
        return copy(host,bidder,MediaRoomState.RECOVERED_AWAITING_BILATERAL_RESUME,reconnectDeadline,pauseBillingFrom);
    }

    public OnlineMediaRoom resumeAfterBilateralAcceptance(Instant now, boolean hostAccepts, boolean bidderAccepts) {
        if (state != MediaRoomState.RECOVERED_AWAITING_BILATERAL_RESUME) throw MediaException.of("MEDIA_BAD_STATE", "Media must be recovered before resume consent");
        if (now.isAfter(reconnectDeadline)) throw MediaException.of("ONLINE_RECONNECT_TIMEOUT", "The 2-minute reconnect window has expired");
        if (!(hostAccepts && bidderAccepts)) throw MediaException.of("ONLINE_BILATERAL_RESUME_REQUIRED", "Both parties must accept resume");
        if (!(host.validVideo() && bidder.validVideo())) throw MediaException.of("ONLINE_CAMERA_REQUIRED", "Valid camera/video is required for both parties");
        return copy(host,bidder,MediaRoomState.PAID_ACTIVE,null,null);
    }

    public OnlineMediaRoom reconnectTimeout(Instant now) {
        if (!(state == MediaRoomState.RECONNECTING || state == MediaRoomState.RECOVERED_AWAITING_BILATERAL_RESUME))
            throw MediaException.of("MEDIA_BAD_STATE", "Reconnect timeout requires reconnect state");
        if (now.isBefore(reconnectDeadline)) throw MediaException.of("ONLINE_RECONNECT_NOT_DUE", "Reconnect deadline has not expired");
        return copy(host,bidder,MediaRoomState.ENDED,reconnectDeadline,pauseBillingFrom);
    }

    public OnlineMediaRoom end() { return copy(host,bidder,MediaRoomState.ENDED,reconnectDeadline,pauseBillingFrom); }

    public Instant lastCommonValidMediaAt() {
        Instant h=host.lastValidMediaAt(), b=bidder.lastValidMediaAt();
        if (h == null || b == null) return null;
        return h.isBefore(b) ? h : b;
    }

    public boolean billableMediaState() { return state == MediaRoomState.PAID_ACTIVE && host.validVideo() && bidder.validVideo(); }
    public boolean persistentRecordingEnabled() { return room.persistentRecordingEnabled(); }
    public UUID appointmentId() { return appointmentId; }
    public RoomHandle room() { return room; }
    public Instant createdAt() { return createdAt; }
    public Instant joinDeadline() { return joinDeadline; }
    public ParticipantMediaState host() { return host; }
    public ParticipantMediaState bidder() { return bidder; }
    public MediaRoomState state() { return state; }
    public Instant reconnectDeadline() { return reconnectDeadline; }
    public Instant pauseBillingFrom() { return pauseBillingFrom; }

    private OnlineMediaRoom copy(ParticipantMediaState h, ParticipantMediaState b, MediaRoomState s, Instant rd, Instant pause) {
        return new OnlineMediaRoom(appointmentId,room,createdAt,joinDeadline,h,b,s,rd,pause);
    }
}

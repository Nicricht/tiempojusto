package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.media.RoomHandle;
import cl.tiempojusto.media.RoomKind;
import cl.tiempojusto.media.TurnCredentials;
import cl.tiempojusto.media.WebRtcPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WebRtcSessionService {
    private static final Duration SIGNAL_TTL = Duration.ofMinutes(2);
    private static final int MAX_SDP_CHARS = 131_072;
    private static final int MAX_CANDIDATE_CHARS = 8_192;

    private final JdbcTemplate jdbc;
    private final WebRtcPort webRtc;

    public WebRtcSessionService(JdbcTemplate jdbc, WebRtcPort webRtc) {
        this.jdbc = jdbc;
        this.webRtc = webRtc;
    }

    @Transactional(readOnly = true)
    public WebRtcConfig config(UUID actorUserId, UUID sessionId) {
        SessionContext ctx = context(sessionId);
        String role = participantRole(actorUserId, ctx);
        ensureUsable(ctx);
        if (ctx.persistentRecordingEnabled()) {
            throw ApiProblem.conflict("PRIVATE_RECORDING_FORBIDDEN", "ONLINE privado no permite grabación persistente.");
        }

        Instant now = Instant.now();
        RoomHandle room = new RoomHandle(ctx.provider(), ctx.providerRoomRef(), RoomKind.ONLINE_PRIVATE, false);
        TurnCredentials credentials = webRtc.issueTurnCredentials(room, actorUserId, now);
        return new WebRtcConfig(
                sessionId,
                ctx.videoRoomId(),
                role,
                "HOST".equals(role),
                false,
                new TurnView(credentials.urls(), credentials.username(), credentials.credential(), credentials.expiresAt()));
    }

    @Transactional
    public SignalAck send(UUID actorUserId, UUID sessionId, SignalRequest request) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw ApiProblem.badRequest("WEBRTC_SIGNAL_REQUIRED", "La señal WebRTC es obligatoria.");
        }
        SessionContext ctx = context(sessionId);
        participantRole(actorUserId, ctx);
        ensureUsable(ctx);

        String type = request.type().trim().toUpperCase(Locale.ROOT);
        validateSignal(type, request);
        UUID recipient = actorUserId.equals(ctx.hostUserId()) ? ctx.bidderUserId() : ctx.hostUserId();
        Instant now = Instant.now();
        purgeExpired(sessionId, now);

        Long sequence = jdbc.queryForObject("""
                insert into media.webrtc_signal(
                    session_id, video_room_id, sender_user_id, recipient_user_id,
                    signal_type, sdp, candidate, sdp_mid, sdp_mline_index,
                    created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                sessionId, ctx.videoRoomId(), actorUserId, recipient, type,
                blankToNull(request.sdp()), blankToNull(request.candidate()), blankToNull(request.sdpMid()),
                request.sdpMLineIndex(), Timestamp.from(now), Timestamp.from(now.plus(SIGNAL_TTL)));

        return new SignalAck(sequence == null ? 0L : sequence, now.plus(SIGNAL_TTL));
    }

    @Transactional
    public SignalBatch poll(UUID actorUserId, UUID sessionId, long after) {
        SessionContext ctx = context(sessionId);
        participantRole(actorUserId, ctx);
        ensureUsable(ctx);
        Instant now = Instant.now();
        purgeExpired(sessionId, now);

        long safeAfter = Math.max(0L, after);
        List<SignalView> signals = jdbc.query("""
                select id, signal_type, sdp, candidate, sdp_mid, sdp_mline_index, created_at
                  from media.webrtc_signal
                 where session_id = ? and recipient_user_id = ? and id > ? and expires_at > ?
                 order by id
                 limit 200
                """, (rs, rowNum) -> new SignalView(
                rs.getLong("id"),
                rs.getString("signal_type"),
                rs.getString("sdp"),
                rs.getString("candidate"),
                rs.getString("sdp_mid"),
                (Integer) rs.getObject("sdp_mline_index"),
                rs.getTimestamp("created_at").toInstant()),
                sessionId, actorUserId, safeAfter, Timestamp.from(now));

        long nextAfter = signals.isEmpty() ? safeAfter : signals.getLast().sequence();
        return new SignalBatch(nextAfter, signals);
    }

    private void validateSignal(String type, SignalRequest request) {
        switch (type) {
            case "OFFER", "ANSWER" -> {
                String sdp = blankToNull(request.sdp());
                if (sdp == null || sdp.length() > MAX_SDP_CHARS) {
                    throw ApiProblem.badRequest("WEBRTC_SDP_INVALID", "SDP faltante o demasiado grande.");
                }
                if (blankToNull(request.candidate()) != null) {
                    throw ApiProblem.badRequest("WEBRTC_SIGNAL_INVALID", "OFFER/ANSWER no acepta candidate.");
                }
            }
            case "ICE_CANDIDATE" -> {
                String candidate = blankToNull(request.candidate());
                if (candidate == null || candidate.length() > MAX_CANDIDATE_CHARS) {
                    throw ApiProblem.badRequest("WEBRTC_CANDIDATE_INVALID", "ICE candidate faltante o demasiado grande.");
                }
                if (blankToNull(request.sdp()) != null) {
                    throw ApiProblem.badRequest("WEBRTC_SIGNAL_INVALID", "ICE_CANDIDATE no acepta SDP.");
                }
                if (request.sdpMLineIndex() != null && (request.sdpMLineIndex() < 0 || request.sdpMLineIndex() > 64)) {
                    throw ApiProblem.badRequest("WEBRTC_MLINE_INVALID", "sdpMLineIndex fuera de rango.");
                }
            }
            case "ICE_COMPLETE" -> {
                if (blankToNull(request.sdp()) != null || blankToNull(request.candidate()) != null) {
                    throw ApiProblem.badRequest("WEBRTC_SIGNAL_INVALID", "ICE_COMPLETE no acepta payload SDP/candidate.");
                }
            }
            default -> throw ApiProblem.badRequest("WEBRTC_SIGNAL_TYPE_INVALID", "Tipo de señal WebRTC no soportado.");
        }
        if (request.sdpMid() != null && request.sdpMid().length() > 128) {
            throw ApiProblem.badRequest("WEBRTC_MID_INVALID", "sdpMid demasiado grande.");
        }
    }

    private void purgeExpired(UUID sessionId, Instant now) {
        jdbc.update("delete from media.webrtc_signal where session_id = ? and expires_at <= ?",
                sessionId, Timestamp.from(now));
    }

    private SessionContext context(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id, s.status::text session_status,
                       a.host_user_id, a.bidder_user_id,
                       vr.id video_room_id, vr.provider_room_ref,
                       coalesce(vr.provider_region, 'MOCK') provider,
                       vr.join_deadline, vr.reconnect_deadline,
                       vr.persistent_recording_enabled
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  join media.video_room vr on vr.appointment_id = a.id
                 where s.id = ?
                """, (rs, rowNum) -> new SessionContext(
                rs.getObject("id", UUID.class),
                rs.getString("session_status"),
                rs.getObject("host_user_id", UUID.class),
                rs.getObject("bidder_user_id", UUID.class),
                rs.getObject("video_room_id", UUID.class),
                rs.getString("provider"),
                rs.getString("provider_room_ref"),
                rs.getTimestamp("join_deadline").toInstant(),
                rs.getTimestamp("reconnect_deadline") == null ? null : rs.getTimestamp("reconnect_deadline").toInstant(),
                rs.getBoolean("persistent_recording_enabled")), sessionId);
        if (rows.isEmpty()) {
            throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");
        }
        return rows.getFirst();
    }

    private void ensureUsable(SessionContext ctx) {
        if ("ENDED".equals(ctx.sessionStatus())) {
            throw ApiProblem.conflict("WEBRTC_SESSION_ENDED", "La sesión ya terminó.");
        }
        Instant now = Instant.now();
        if ("WAITING".equals(ctx.sessionStatus()) && now.isAfter(ctx.joinDeadline())) {
            throw ApiProblem.conflict("ONLINE_JOIN_TIMEOUT", "La ventana Online de 3 minutos venció.");
        }
        if ("RECONNECTING".equals(ctx.sessionStatus())
                && ctx.reconnectDeadline() != null
                && now.isAfter(ctx.reconnectDeadline())) {
            throw ApiProblem.conflict("ONLINE_RECONNECT_TIMEOUT", "La ventana de reconexión de 2 minutos venció.");
        }
    }

    private String participantRole(UUID actorUserId, SessionContext ctx) {
        if (actorUserId.equals(ctx.hostUserId())) return "HOST";
        if (actorUserId.equals(ctx.bidderUserId())) return "BIDDER";
        throw ApiProblem.forbidden("SESSION_PARTICIPANT_REQUIRED", "Actor no participa en esta sesión.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SessionContext(UUID id, String sessionStatus, UUID hostUserId, UUID bidderUserId,
                                  UUID videoRoomId, String provider, String providerRoomRef,
                                  Instant joinDeadline, Instant reconnectDeadline,
                                  boolean persistentRecordingEnabled) {}

    public record WebRtcConfig(UUID sessionId, UUID videoRoomId, String participantRole,
                               boolean initiator, boolean persistentRecordingEnabled, TurnView turn) {}
    public record TurnView(List<String> urls, String username, String credential, Instant expiresAt) {}
    public record SignalRequest(String type, String sdp, String candidate, String sdpMid, Integer sdpMLineIndex) {}
    public record SignalAck(long sequence, Instant expiresAt) {}
    public record SignalView(long sequence, String type, String sdp, String candidate,
                             String sdpMid, Integer sdpMLineIndex, Instant createdAt) {}
    public record SignalBatch(long nextAfter, List<SignalView> signals) {}
}

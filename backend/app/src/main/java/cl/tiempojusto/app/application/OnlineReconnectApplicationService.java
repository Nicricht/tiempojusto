package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.media.RoomHandle;
import cl.tiempojusto.media.RoomKind;
import cl.tiempojusto.media.WebRtcPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OnlineReconnectApplicationService {
    private static final Duration MICRO_CUT_TOLERANCE = Duration.ofSeconds(5);
    private static final Duration RECONNECT_WINDOW = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;
    private final WebRtcPort webRtc;

    public OnlineReconnectApplicationService(JdbcTemplate jdbc,
                                             ApplicationPersistenceSupport persistence,
                                             WebRtcPort webRtc) {
        this.jdbc = jdbc;
        this.persistence = persistence;
        this.webRtc = webRtc;
    }

    @Transactional
    public StateView signal(UUID actorUserId, UUID sessionId, MediaSignalRequest request) {
        if (request == null) {
            throw ApiProblem.badRequest("MEDIA_SIGNAL_REQUIRED", "El estado de media es obligatorio.");
        }
        SessionContext ctx = lockContext(sessionId);
        String role = participantRole(actorUserId, ctx);
        if (!("PAID_ACTIVE".equals(ctx.sessionStatus()) || "RECONNECTING".equals(ctx.sessionStatus()))) {
            throw ApiProblem.conflict("MEDIA_SIGNAL_BAD_STATE",
                    "Las señales de reconexión aplican solo durante PAID_ACTIVE o RECONNECTING.");
        }

        Instant now = Instant.now();
        boolean validMedia = request.cameraValid() && request.mediaFlowing();
        jdbc.update("""
                update media.video_participant_state
                   set camera_valid = ?, media_flowing = ?, audio_muted = ?,
                       last_heartbeat_at = ?,
                       last_valid_media_at = case when ? then ? else last_valid_media_at end,
                       updated_at = ?
                 where video_room_id = ? and user_id = ?
                """, request.cameraValid(), request.mediaFlowing(), request.audioMuted(),
                Timestamp.from(now), validMedia, Timestamp.from(now), Timestamp.from(now),
                ctx.videoRoomId(), actorUserId);

        jdbc.update("""
                insert into media.video_participant_event(video_room_id, user_id, event_type, server_at, metadata)
                values (?, ?, 'MEDIA_HEARTBEAT', ?, jsonb_build_object(
                    'cameraValid', ?, 'mediaFlowing', ?, 'audioMuted', ?, 'role', ?))
                """, ctx.videoRoomId(), actorUserId, Timestamp.from(now),
                request.cameraValid(), request.mediaFlowing(), request.audioMuted(), role);

        if ("PAID_ACTIVE".equals(ctx.sessionStatus())) {
            maybeEnterReconnect(ctx, now);
        } else {
            if (isReconnectExpired(ctx, now)) {
                finishReconnectTimeout(ctx, ctx.reconnectDeadline());
            } else {
                updateRecoveryState(ctx, now);
            }
        }
        return get(sessionId);
    }

    @Transactional
    public StateView acceptResume(UUID actorUserId, UUID sessionId) {
        SessionContext ctx = lockContext(sessionId);
        participantRole(actorUserId, ctx);
        if (!"RECONNECTING".equals(ctx.sessionStatus())) {
            if ("PAID_ACTIVE".equals(ctx.sessionStatus())) return get(sessionId);
            throw ApiProblem.conflict("ONLINE_RESUME_BAD_STATE", "La sesión no está esperando reconexión.");
        }

        Instant now = Instant.now();
        if (isReconnectExpired(ctx, now)) {
            finishReconnectTimeout(ctx, ctx.reconnectDeadline());
            throw ApiProblem.conflict("ONLINE_RECONNECT_TIMEOUT", "La ventana de reconexión de 2 minutos venció.");
        }

        IncidentRow incident = latestIncident(ctx.videoRoomId());
        if (incident == null || incident.recoveredAt() == null) {
            throw ApiProblem.conflict("ONLINE_MEDIA_NOT_RECOVERED",
                    "La conexión debe recuperarse antes de aceptar la reanudación.");
        }
        if (readyParticipants(ctx.videoRoomId()) != 2) {
            throw ApiProblem.conflict("ONLINE_MEDIA_NOT_READY",
                    "HOST y BIDDER deben tener cámara y media válidas para reanudar.");
        }

        Boolean alreadyAccepted = jdbc.queryForObject("""
                select exists(
                    select 1 from media.video_participant_event
                     where video_room_id = ? and user_id = ? and event_type = 'RESUME_ACCEPTED'
                       and server_at >= ?)
                """, Boolean.class, ctx.videoRoomId(), actorUserId, Timestamp.from(incident.recoveredAt()));
        if (!Boolean.TRUE.equals(alreadyAccepted)) {
            jdbc.update("""
                    insert into media.video_participant_event(video_room_id, user_id, event_type, server_at, metadata)
                    values (?, ?, 'RESUME_ACCEPTED', ?, jsonb_build_object('incidentId', ?))
                    """, ctx.videoRoomId(), actorUserId, Timestamp.from(now), incident.id());
        }

        int acceptances = resumeAcceptanceCount(ctx.videoRoomId(), incident.recoveredAt());
        if (acceptances == 2) {
            jdbc.update("""
                    update appointment.session_segment
                       set ended_at = ?
                     where session_id = ? and segment_type = 'RECONNECT' and ended_at is null
                    """, Timestamp.from(now), sessionId);
            jdbc.update("""
                    update appointment.appointment_session
                       set status = 'PAID_ACTIVE', lock_version = lock_version + 1
                     where id = ?
                    """, sessionId);
            jdbc.update("""
                    update media.video_room
                       set status = 'PAID_ACTIVE', media_lost_at = null, reconnect_deadline = null
                     where id = ?
                    """, ctx.videoRoomId());
            jdbc.update("""
                    insert into appointment.session_segment(session_id, segment_type, started_at, billable)
                    values (?, 'PAID', ?, true)
                    """, sessionId, Timestamp.from(now));

            persistence.audit(actorUserId, "ONLINE_SESSION_RESUMED", "SESSION", sessionId,
                    Map.of("incidentId", incident.id(), "resumeAcceptances", 2));
            persistence.outbox("SESSION", sessionId, "SESSION_RESUMED",
                    Map.of("sessionId", sessionId, "incidentId", incident.id(), "resumedAt", now));
        } else {
            persistence.audit(actorUserId, "ONLINE_RESUME_ACCEPTED", "SESSION", sessionId,
                    Map.of("incidentId", incident.id(), "resumeAcceptances", acceptances));
        }
        return get(sessionId);
    }

    @Transactional
    public StateView evaluate(UUID sessionId) {
        SessionContext ctx = lockContext(sessionId);
        Instant now = Instant.now();
        if ("PAID_ACTIVE".equals(ctx.sessionStatus())) {
            maybeEnterReconnect(ctx, now);
        } else if ("RECONNECTING".equals(ctx.sessionStatus())) {
            if (isReconnectExpired(ctx, now)) finishReconnectTimeout(ctx, ctx.reconnectDeadline());
            else updateRecoveryState(ctx, now);
        }
        return get(sessionId);
    }

    @Transactional(readOnly = true)
    public List<UUID> dueReconnectSessions() {
        return jdbc.query("""
                select s.id
                  from appointment.appointment_session s
                  join media.video_room vr on vr.appointment_id = s.appointment_id
                 where s.session_mode = 'ONLINE' and s.status = 'RECONNECTING'
                   and vr.reconnect_deadline is not null and vr.reconnect_deadline <= clock_timestamp()
                 order by vr.reconnect_deadline
                 limit 100
                """, (rs, n) -> rs.getObject(1, UUID.class));
    }

    @Transactional(readOnly = true)
    public StateView get(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id, s.status::text session_status, s.billable_seconds,
                       vr.id video_room_id, vr.status::text video_status,
                       vr.media_lost_at, vr.reconnect_deadline, vr.persistent_recording_enabled
                  from appointment.appointment_session s
                  join media.video_room vr on vr.appointment_id = s.appointment_id
                 where s.id = ?
                """, (rs, n) -> {
            UUID videoRoomId = rs.getObject("video_room_id", UUID.class);
            IncidentRow incident = latestIncident(videoRoomId);
            Instant lastCommon = lastCommonValidMedia(videoRoomId);
            Instant recoveredAt = incident == null ? null : incident.recoveredAt();
            int accepts = recoveredAt == null ? 0 : resumeAcceptanceCount(videoRoomId, recoveredAt);
            return new StateView(
                    rs.getObject("id", UUID.class), rs.getString("session_status"), videoRoomId,
                    rs.getString("video_status"), readyParticipants(videoRoomId),
                    instant(rs.getTimestamp("media_lost_at")), instant(rs.getTimestamp("reconnect_deadline")),
                    lastCommon, recoveredAt, accepts, rs.getInt("billable_seconds"),
                    rs.getBoolean("persistent_recording_enabled"));
        }, sessionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");
        return rows.getFirst();
    }

    private void maybeEnterReconnect(SessionContext ctx, Instant now) {
        if (readyParticipants(ctx.videoRoomId()) == 2) return;
        Instant lastCommon = lastCommonValidMedia(ctx.videoRoomId());
        if (lastCommon == null || !now.isAfter(lastCommon.plus(MICRO_CUT_TOLERANCE))) return;

        Instant openPaidStartedAt = openSegmentStartedAt(ctx.id(), "PAID");
        Instant pauseFrom = openPaidStartedAt == null || lastCommon.isAfter(openPaidStartedAt)
                ? lastCommon : openPaidStartedAt;
        int segmentSeconds = openPaidStartedAt == null ? 0
                : safeSeconds(Duration.between(openPaidStartedAt, pauseFrom));

        jdbc.update("""
                update appointment.session_segment
                   set ended_at = ?, billable_seconds = ?
                 where session_id = ? and segment_type = 'PAID' and ended_at is null
                """, Timestamp.from(pauseFrom), segmentSeconds, ctx.id());
        jdbc.update("""
                insert into appointment.session_segment(session_id, segment_type, started_at, billable)
                values (?, 'RECONNECT', ?, false)
                """, ctx.id(), Timestamp.from(pauseFrom));

        Instant deadline = now.plus(RECONNECT_WINDOW);
        jdbc.update("""
                update appointment.appointment_session
                   set status = 'RECONNECTING', billable_seconds = ?, lock_version = lock_version + 1
                 where id = ?
                """, totalBillableSeconds(ctx.id(), ctx.durationMinutes()), ctx.id());
        jdbc.update("""
                update media.video_room
                   set status = 'RECONNECTING', media_lost_at = ?, reconnect_deadline = ?
                 where id = ?
                """, Timestamp.from(now), Timestamp.from(deadline), ctx.videoRoomId());
        Long incidentId = jdbc.queryForObject("""
                insert into media.media_incident(
                    video_room_id, incident_type, detected_at, last_valid_media_at, metadata)
                values (?, 'ONLINE_MEDIA_INTERRUPTION', ?, ?, jsonb_build_object('pauseBillingFrom', ?::text))
                returning id
                """, Long.class, ctx.videoRoomId(), Timestamp.from(now), Timestamp.from(pauseFrom), pauseFrom.toString());

        persistence.audit(null, "ONLINE_MEDIA_INTERRUPTION_CONFIRMED", "SESSION", ctx.id(),
                Map.of("incidentId", incidentId, "detectedAt", now, "pauseBillingFrom", pauseFrom,
                        "reconnectDeadline", deadline));
        persistence.outbox("SESSION", ctx.id(), "ONLINE_CONNECTION_LOST",
                Map.of("sessionId", ctx.id(), "incidentId", incidentId, "detectedAt", now,
                        "reconnectDeadline", deadline));
        persistence.outbox("SESSION", ctx.id(), "BILLING_PAUSED_CONNECTION",
                Map.of("sessionId", ctx.id(), "pauseBillingFrom", pauseFrom));
    }

    private void updateRecoveryState(SessionContext ctx, Instant now) {
        IncidentRow incident = latestIncident(ctx.videoRoomId());
        if (incident == null) return;
        int ready = readyParticipants(ctx.videoRoomId());
        if (ready == 2 && incident.recoveredAt() == null) {
            jdbc.update("update media.media_incident set recovered_at = ? where id = ?",
                    Timestamp.from(now), incident.id());
            persistence.audit(null, "ONLINE_MEDIA_RECOVERED", "SESSION", ctx.id(),
                    Map.of("incidentId", incident.id(), "recoveredAt", now,
                            "billingResumed", false));
            persistence.outbox("SESSION", ctx.id(), "ONLINE_MEDIA_RECOVERED",
                    Map.of("sessionId", ctx.id(), "incidentId", incident.id(),
                            "recoveredAt", now, "requiresBilateralResume", true));
        } else if (ready < 2 && incident.recoveredAt() != null) {
            jdbc.update("update media.media_incident set recovered_at = null where id = ?", incident.id());
            jdbc.update("""
                    insert into media.video_participant_event(video_room_id, user_id, event_type, server_at, metadata)
                    select ?, user_id, 'RECOVERY_INVALIDATED', ?, jsonb_build_object('incidentId', ?)
                      from media.video_participant_state
                     where video_room_id = ? and (camera_valid = false or media_flowing = false)
                    """, ctx.videoRoomId(), Timestamp.from(now), incident.id(), ctx.videoRoomId());
        }
    }

    private void finishReconnectTimeout(SessionContext ctx, Instant terminalAt) {
        if (terminalAt == null) terminalAt = Instant.now();
        jdbc.update("""
                update appointment.session_segment
                   set ended_at = ?
                 where session_id = ? and segment_type = 'RECONNECT' and ended_at is null
                """, Timestamp.from(terminalAt), ctx.id());
        int billable = totalBillableSeconds(ctx.id(), ctx.durationMinutes());
        String settlementState = settlementState(ctx.agreedAmountClp(), ctx.durationMinutes(), billable);

        jdbc.update("""
                update appointment.appointment_session
                   set status = 'ENDED', ended_at = ?, billable_seconds = ?, lock_version = lock_version + 1
                 where id = ?
                """, Timestamp.from(terminalAt), billable, ctx.id());
        jdbc.update("update appointment.appointment set status = 'ENDED', lock_version = lock_version + 1 where id = ?",
                ctx.appointmentId());
        jdbc.update("update media.video_room set status = 'ENDED' where id = ?", ctx.videoRoomId());

        try {
            webRtc.closeRoom(new RoomHandle(ctx.provider(), ctx.providerRoomRef(), RoomKind.ONLINE_PRIVATE, false), terminalAt);
        } catch (RuntimeException ignored) {
            // Persisted lifecycle is authoritative even if provider close already happened or is unavailable.
        }

        persistence.audit(null, "ONLINE_RECONNECT_TIMEOUT", "SESSION", ctx.id(),
                Map.of("endedAt", terminalAt, "billableSeconds", billable, "settlementState", settlementState));
        persistence.outbox("SESSION", ctx.id(), "SESSION_FINISHED",
                Map.of("sessionId", ctx.id(), "reason", "FINISHED_RECONNECT_TIMEOUT",
                        "endedAt", terminalAt, "billableSeconds", billable,
                        "settlementState", settlementState));
    }

    private SessionContext lockContext(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id, s.appointment_id, s.status::text session_status,
                       a.host_user_id, a.bidder_user_id, a.duration_minutes, a.agreed_amount_clp,
                       vr.id video_room_id, vr.provider_room_ref,
                       coalesce(vr.provider_region, 'MOCK') provider,
                       vr.media_lost_at, vr.reconnect_deadline
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  join media.video_room vr on vr.appointment_id = a.id
                 where s.id = ?
                 for update of s, vr
                """, (rs, n) -> new SessionContext(
                rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class),
                rs.getString("session_status"), rs.getObject("host_user_id", UUID.class),
                rs.getObject("bidder_user_id", UUID.class), rs.getInt("duration_minutes"),
                rs.getLong("agreed_amount_clp"), rs.getObject("video_room_id", UUID.class),
                rs.getString("provider"), rs.getString("provider_room_ref"),
                instant(rs.getTimestamp("media_lost_at")), instant(rs.getTimestamp("reconnect_deadline"))), sessionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");
        return rows.getFirst();
    }

    private String participantRole(UUID actor, SessionContext ctx) {
        if (actor.equals(ctx.hostUserId())) return "HOST";
        if (actor.equals(ctx.bidderUserId())) return "BIDDER";
        throw ApiProblem.forbidden("SESSION_PARTICIPANT_REQUIRED", "Actor no participa en esta sesión.");
    }

    private int readyParticipants(UUID videoRoomId) {
        Integer ready = jdbc.queryForObject("""
                select count(*) from media.video_participant_state
                 where video_room_id = ? and joined_at is not null and left_at is null
                   and camera_valid = true and media_flowing = true
                   and participant_role in ('HOST','BIDDER')
                """, Integer.class, videoRoomId);
        return ready == null ? 0 : ready;
    }

    private Instant lastCommonValidMedia(UUID videoRoomId) {
        Timestamp ts = jdbc.query("""
                select min(last_valid_media_at)
                  from media.video_participant_state
                 where video_room_id = ? and participant_role in ('HOST','BIDDER')
                having count(*) = 2 and count(last_valid_media_at) = 2
                """, rs -> rs.next() ? rs.getTimestamp(1) : null, videoRoomId);
        return instant(ts);
    }

    private Instant openSegmentStartedAt(UUID sessionId, String type) {
        Timestamp ts = jdbc.query("""
                select started_at from appointment.session_segment
                 where session_id = ? and segment_type::text = ? and ended_at is null
                 order by id desc limit 1
                """, rs -> rs.next() ? rs.getTimestamp(1) : null, sessionId, type);
        return instant(ts);
    }

    private IncidentRow latestIncident(UUID videoRoomId) {
        return jdbc.query("""
                select id, detected_at, last_valid_media_at, recovered_at
                  from media.media_incident
                 where video_room_id = ? and incident_type = 'ONLINE_MEDIA_INTERRUPTION'
                 order by detected_at desc, id desc limit 1
                """, rs -> rs.next() ? new IncidentRow(
                        rs.getLong("id"), rs.getTimestamp("detected_at").toInstant(),
                        instant(rs.getTimestamp("last_valid_media_at")),
                        instant(rs.getTimestamp("recovered_at"))) : null, videoRoomId);
    }

    private int resumeAcceptanceCount(UUID videoRoomId, Instant recoveredAt) {
        Integer count = jdbc.queryForObject("""
                select count(distinct user_id)
                  from media.video_participant_event
                 where video_room_id = ? and event_type = 'RESUME_ACCEPTED' and server_at >= ?
                """, Integer.class, videoRoomId, Timestamp.from(recoveredAt));
        return count == null ? 0 : count;
    }

    private int totalBillableSeconds(UUID sessionId, int durationMinutes) {
        Long total = jdbc.queryForObject("""
                select coalesce(sum(billable_seconds), 0)
                  from appointment.session_segment
                 where session_id = ? and segment_type = 'PAID'
                """, Long.class, sessionId);
        long cap = (long) durationMinutes * 60L;
        return (int) Math.min(cap, total == null ? 0L : total);
    }

    private static boolean isReconnectExpired(SessionContext ctx, Instant now) {
        return ctx.reconnectDeadline() != null && !now.isBefore(ctx.reconnectDeadline());
    }

    private static int safeSeconds(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static String settlementState(long amountClp, int durationMinutes, int billableSeconds) {
        long fullSeconds = (long) durationMinutes * 60L;
        if (billableSeconds >= fullSeconds) return "READY_FOR_SETTLEMENT";
        long numerator = Math.multiplyExact(amountClp, billableSeconds);
        return numerator % fullSeconds == 0 ? "READY_FOR_SETTLEMENT" : "PENDING_ROUNDING_POLICY";
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record SessionContext(UUID id, UUID appointmentId, String sessionStatus,
                                  UUID hostUserId, UUID bidderUserId, int durationMinutes,
                                  long agreedAmountClp, UUID videoRoomId, String provider,
                                  String providerRoomRef, Instant mediaLostAt, Instant reconnectDeadline) {}

    private record IncidentRow(long id, Instant detectedAt, Instant lastValidMediaAt, Instant recoveredAt) {}

    public record MediaSignalRequest(boolean cameraValid, boolean mediaFlowing, boolean audioMuted) {}

    public record StateView(UUID sessionId, String sessionStatus, UUID videoRoomId, String videoStatus,
                            int readyParticipants, Instant mediaLostAt, Instant reconnectDeadline,
                            Instant lastCommonValidMediaAt, Instant recoveredAt, int resumeAcceptances,
                            int billableSeconds, boolean persistentRecordingEnabled) {}
}

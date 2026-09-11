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
import java.util.Map;
import java.util.UUID;

@Service
public class OnlineApplicationService {
    private final JdbcTemplate jdbc;
    private final WebRtcPort webRtc;
    private final ApplicationPersistenceSupport persistence;

    public OnlineApplicationService(JdbcTemplate jdbc, WebRtcPort webRtc,
                                    ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.webRtc = webRtc;
        this.persistence = persistence;
    }

    @Transactional
    public ConfirmResult confirmWinner(UUID actorUserId, UUID appointmentId) {
        AppointmentRow ap = lockAppointment(appointmentId);
        if (!ap.bidderUserId().equals(actorUserId)) {
            throw ApiProblem.forbidden("WINNER_CONFIRM_FORBIDDEN", "Solo el BIDDER ganador puede confirmar.");
        }
        if ("CONFIRMED".equals(ap.status())) {
            UUID sessionId = existingSessionId(appointmentId);
            UUID roomId = existingRoomId(appointmentId);
            return new ConfirmResult(appointmentId, sessionId, roomId, "CONFIRMED", true);
        }
        if (!"AWAITING_CONFIRMATION".equals(ap.status())) {
            throw ApiProblem.conflict("APPOINTMENT_NOT_AWAITING_CONFIRMATION", "Appointment no espera confirmación.");
        }

        Instant now = Instant.now();
        Instant deadline = jdbc.queryForObject("""
                select confirmation_deadline from auction.auction_participant
                 where auction_id = ? and bidder_user_id = ?
                """, Timestamp.class, ap.auctionId(), actorUserId).toInstant();
        if (now.isAfter(deadline)) {
            jdbc.update("update auction.auction_participant set confirmation_status = 'EXPIRED' where auction_id = ? and bidder_user_id = ?",
                    ap.auctionId(), actorUserId);
            throw ApiProblem.conflict("WINNER_CONFIRMATION_EXPIRED", "La ventana de confirmación de 3 minutos venció.");
        }

        jdbc.update("""
                update appointment.appointment
                   set status = 'CONFIRMED', winner_confirmed_at = ?, lock_version = lock_version + 1
                 where id = ?
                """, Timestamp.from(now), appointmentId);
        jdbc.update("""
                update auction.auction_participant
                   set confirmation_status = 'CONFIRMED', last_active_at = ?
                 where auction_id = ? and bidder_user_id = ?
                """, Timestamp.from(now), ap.auctionId(), actorUserId);

        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                insert into appointment.appointment_session(id, appointment_id, session_mode, status)
                values (?, ?, 'ONLINE', 'WAITING')
                """, sessionId, appointmentId);

        RoomHandle room = webRtc.createRoom(RoomKind.ONLINE_PRIVATE, sessionId, false, now);
        UUID roomId = UUID.randomUUID();
        jdbc.update("""
                insert into media.video_room(
                    id, appointment_id, provider_room_ref, status, join_deadline,
                    created_at, persistent_recording_enabled)
                values (?, ?, ?, 'WAITING', ?, ?, false)
                """, roomId, appointmentId, room.providerRoomRef(), Timestamp.from(now.plusSeconds(180)), Timestamp.from(now));

        persistence.audit(actorUserId, "WINNER_CONFIRMED", "APPOINTMENT", appointmentId,
                Map.of("sessionId", sessionId.toString(), "videoRoomId", roomId.toString()));
        persistence.outbox("APPOINTMENT", appointmentId, "WINNER_CONFIRMED",
                Map.of("appointmentId", appointmentId.toString(), "sessionId", sessionId.toString()));
        persistence.outbox("SESSION", sessionId, "ONLINE_JOIN_WINDOW_STARTED",
                Map.of("sessionId", sessionId.toString(), "joinDeadline", now.plusSeconds(180).toString()));

        return new ConfirmResult(appointmentId, sessionId, roomId, "CONFIRMED", false);
    }

    @Transactional
    public JoinResult join(UUID actorUserId, UUID sessionId, JoinRequest request) {
        if (request == null || !request.cameraValid() || !request.mediaFlowing()) {
            throw ApiProblem.conflict("ONLINE_CAMERA_MEDIA_REQUIRED", "JOIN requiere cámara válida y media fluyendo.");
        }
        SessionContext ctx = lockSessionContext(sessionId);
        String role = participantRole(actorUserId, ctx);
        Instant now = Instant.now();
        if (now.isAfter(ctx.joinDeadline())) {
            throw ApiProblem.conflict("ONLINE_JOIN_TIMEOUT", "La ventana Online de 3 minutos venció.");
        }
        if (!"WAITING".equals(ctx.sessionStatus()) && !"FREE_ACTIVE".equals(ctx.sessionStatus())) {
            throw ApiProblem.conflict("ONLINE_JOIN_BAD_STATE", "La sesión no admite JOIN en su estado actual.");
        }

        jdbc.update("""
                insert into media.video_participant_state(
                    video_room_id, user_id, participant_role, joined_at, camera_valid,
                    media_flowing, audio_muted, last_heartbeat_at, last_valid_media_at, updated_at)
                values (?, ?, ?, ?, true, true, ?, ?, ?, ?)
                on conflict (video_room_id, user_id) do update
                    set joined_at = coalesce(media.video_participant_state.joined_at, excluded.joined_at),
                        left_at = null,
                        camera_valid = true,
                        media_flowing = true,
                        audio_muted = excluded.audio_muted,
                        last_heartbeat_at = excluded.last_heartbeat_at,
                        last_valid_media_at = excluded.last_valid_media_at,
                        updated_at = excluded.updated_at
                """, ctx.videoRoomId(), actorUserId, role, Timestamp.from(now), request.audioMuted(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into media.video_participant_event(video_room_id, user_id, event_type, server_at, metadata)
                values (?, ?, 'JOINED_VALID_MEDIA', ?, '{}'::jsonb)
                """, ctx.videoRoomId(), actorUserId, Timestamp.from(now));

        Integer ready = jdbc.queryForObject("""
                select count(*) from media.video_participant_state
                 where video_room_id = ? and joined_at is not null and left_at is null
                   and camera_valid = true and media_flowing = true
                   and participant_role in ('HOST','BIDDER')
                """, Integer.class, ctx.videoRoomId());

        Instant freeEndsAt = ctx.freeEndsAt();
        if (ready != null && ready == 2 && "WAITING".equals(ctx.sessionStatus())) {
            freeEndsAt = now.plusSeconds(120);
            jdbc.update("""
                    update appointment.appointment_session
                       set status = 'FREE_ACTIVE', free_started_at = ?, free_ends_at = ?, lock_version = lock_version + 1
                     where id = ?
                    """, Timestamp.from(now), Timestamp.from(freeEndsAt), sessionId);
            jdbc.update("""
                    update media.video_room
                       set status = 'FREE_ONLINE', free_online_end = ?
                     where id = ?
                    """, Timestamp.from(freeEndsAt), ctx.videoRoomId());
            jdbc.update("""
                    insert into appointment.session_segment(session_id, segment_type, started_at, billable)
                    values (?, 'FREE', ?, false)
                    """, sessionId, Timestamp.from(now));
            persistence.outbox("SESSION", sessionId, "FREE_PERIOD_STARTED",
                    Map.of("sessionId", sessionId.toString(), "freeEndsAt", freeEndsAt.toString(), "seconds", 120));
        }

        RoomHandle handle = new RoomHandle(ctx.provider(), ctx.providerRoomRef(), RoomKind.ONLINE_PRIVATE, false);
        TurnCredentials turn = webRtc.issueTurnCredentials(handle, actorUserId, now);
        return new JoinResult(sessionId, ctx.videoRoomId(), role, ready == null ? 0 : ready,
                ready != null && ready == 2 ? "FREE_ACTIVE" : "WAITING", freeEndsAt,
                new TurnView(turn.urls(), turn.username(), turn.credential(), turn.expiresAt()));
    }

    @Transactional
    public SessionView acceptPaid(UUID actorUserId, UUID sessionId) {
        SessionContext ctx = lockSessionContext(sessionId);
        participantRole(actorUserId, ctx);
        if (!("FREE_ACTIVE".equals(ctx.sessionStatus()) || "PAID_PENDING_ACCEPTANCE".equals(ctx.sessionStatus()))) {
            if ("PAID_ACTIVE".equals(ctx.sessionStatus())) return get(sessionId);
            throw ApiProblem.conflict("ONLINE_PAID_CONSENT_BAD_STATE", "La sesión no espera aceptación de pago.");
        }
        Instant now = Instant.now();
        if (ctx.freeEndsAt() == null || now.isBefore(ctx.freeEndsAt())) {
            throw ApiProblem.conflict("FREE_ONLINE_NOT_FINISHED", "FREE_ONLINE debe completar 2 minutos antes del cobro.");
        }
        Instant consentDeadline = ctx.freeEndsAt().plusSeconds(30);
        if (now.isAfter(consentDeadline)) {
            jdbc.update("update appointment.appointment_session set status = 'ENDED', ended_at = ?, lock_version = lock_version + 1 where id = ?",
                    Timestamp.from(now), sessionId);
            jdbc.update("update media.video_room set status = 'ENDED', paid_acceptance_deadline = ? where id = ?",
                    Timestamp.from(consentDeadline), ctx.videoRoomId());
            throw ApiProblem.conflict("ONLINE_PAID_CONSENT_TIMEOUT", "Ventana bilateral de 30 segundos vencida.");
        }

        Boolean accepted = jdbc.queryForObject("""
                select exists(select 1 from media.video_participant_event
                               where video_room_id = ? and user_id = ? and event_type = 'PAID_ACCEPTED')
                """, Boolean.class, ctx.videoRoomId(), actorUserId);
        if (!Boolean.TRUE.equals(accepted)) {
            jdbc.update("""
                    insert into media.video_participant_event(video_room_id, user_id, event_type, server_at, metadata)
                    values (?, ?, 'PAID_ACCEPTED', ?, '{}'::jsonb)
                    """, ctx.videoRoomId(), actorUserId, Timestamp.from(now));
        }
        jdbc.update("""
                update media.video_room
                   set status = 'ACCEPTANCE_PENDING', paid_acceptance_deadline = ?
                 where id = ? and status <> 'PAID_ACTIVE'
                """, Timestamp.from(consentDeadline), ctx.videoRoomId());

        Integer count = jdbc.queryForObject("""
                select count(distinct user_id) from media.video_participant_event
                 where video_room_id = ? and event_type = 'PAID_ACCEPTED'
                   and server_at <= ?
                """, Integer.class, ctx.videoRoomId(), Timestamp.from(consentDeadline));
        if (count != null && count == 2) {
            jdbc.update("""
                    update appointment.appointment_session
                       set status = 'PAID_ACTIVE', paid_started_at = ?, lock_version = lock_version + 1
                     where id = ?
                    """, Timestamp.from(now), sessionId);
            jdbc.update("update media.video_room set status = 'PAID_ACTIVE' where id = ?", ctx.videoRoomId());
            jdbc.update("""
                    update appointment.session_segment set ended_at = ?
                     where session_id = ? and segment_type = 'FREE' and ended_at is null
                    """, Timestamp.from(ctx.freeEndsAt()), sessionId);
            jdbc.update("""
                    insert into appointment.session_segment(session_id, segment_type, started_at, billable)
                    values (?, 'PAID', ?, true)
                    """, sessionId, Timestamp.from(now));
            persistence.outbox("SESSION", sessionId, "PAID_ACTIVE_STARTED",
                    Map.of("sessionId", sessionId.toString(), "paidStartedAt", now.toString()));
        } else {
            jdbc.update("""
                    update appointment.appointment_session
                       set status = 'PAID_PENDING_ACCEPTANCE', lock_version = lock_version + 1
                     where id = ? and status = 'FREE_ACTIVE'
                    """, sessionId);
        }
        persistence.audit(actorUserId, "ONLINE_PAID_ACCEPTED", "SESSION", sessionId,
                Map.of("acceptanceCount", count == null ? 0 : count));
        return get(sessionId);
    }

    @Transactional
    public FinishResult finish(UUID actorUserId, UUID sessionId) {
        SessionContext ctx = lockSessionContext(sessionId);
        participantRole(actorUserId, ctx);
        if ("ENDED".equals(ctx.sessionStatus())) {
            SessionView view = get(sessionId);
            return new FinishResult(view, view.billableSeconds(), false, "ALREADY_ENDED");
        }

        Instant now = Instant.now();
        int billableSeconds = 0;
        boolean proportionalSettlementNeedsRoundingPolicy = false;
        if ("PAID_ACTIVE".equals(ctx.sessionStatus()) && ctx.paidStartedAt() != null) {
            long fullSeconds = (long) ctx.durationMinutes() * 60L;
            long elapsed = Math.max(0L, Duration.between(ctx.paidStartedAt(), now).getSeconds());
            billableSeconds = (int) Math.min(fullSeconds, elapsed);
            long numerator = Math.multiplyExact(ctx.agreedAmountClp(), billableSeconds);
            proportionalSettlementNeedsRoundingPolicy = billableSeconds < fullSeconds && numerator % fullSeconds != 0;
            jdbc.update("""
                    update appointment.session_segment
                       set ended_at = ?, billable_seconds = ?
                     where session_id = ? and segment_type = 'PAID' and ended_at is null
                    """, Timestamp.from(now), billableSeconds, sessionId);
        } else {
            jdbc.update("""
                    update appointment.session_segment set ended_at = ?
                     where session_id = ? and ended_at is null
                    """, Timestamp.from(now), sessionId);
        }

        jdbc.update("""
                update appointment.appointment_session
                   set status = 'ENDED', ended_at = ?, billable_seconds = ?, lock_version = lock_version + 1
                 where id = ?
                """, Timestamp.from(now), billableSeconds, sessionId);
        jdbc.update("update appointment.appointment set status = 'ENDED', lock_version = lock_version + 1 where id = ?",
                ctx.appointmentId());
        jdbc.update("update media.video_room set status = 'ENDED' where id = ?", ctx.videoRoomId());

        try {
            webRtc.closeRoom(new RoomHandle(ctx.provider(), ctx.providerRoomRef(), RoomKind.ONLINE_PRIVATE, false), now);
        } catch (RuntimeException ignored) {
            // The persisted state remains authoritative even if a mock/provider room is already gone.
        }

        String settlementState = proportionalSettlementNeedsRoundingPolicy
                ? "PENDING_ROUNDING_POLICY" : "READY_FOR_SETTLEMENT";
        persistence.audit(actorUserId, "SESSION_FINISHED", "SESSION", sessionId,
                Map.of("billableSeconds", billableSeconds, "settlementState", settlementState));
        persistence.outbox("SESSION", sessionId, "SESSION_FINISHED",
                Map.of("sessionId", sessionId.toString(), "billableSeconds", billableSeconds,
                        "settlementState", settlementState));

        return new FinishResult(get(sessionId), billableSeconds,
                proportionalSettlementNeedsRoundingPolicy, settlementState);
    }

    @Transactional(readOnly = true)
    public SessionView get(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id, s.appointment_id, s.status::text, s.free_started_at, s.free_ends_at,
                       s.paid_started_at, s.ended_at, s.billable_seconds, s.lock_version,
                       a.host_user_id, a.bidder_user_id, a.duration_minutes, a.agreed_amount_clp,
                       vr.id video_room_id, vr.status::text video_status, vr.join_deadline,
                       vr.paid_acceptance_deadline, vr.persistent_recording_enabled
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  join media.video_room vr on vr.appointment_id = a.id
                 where s.id = ?
                """, (rs, n) -> new SessionView(
                rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class),
                rs.getObject("host_user_id", UUID.class), rs.getObject("bidder_user_id", UUID.class),
                rs.getString("status"), rs.getInt("duration_minutes"), rs.getLong("agreed_amount_clp"),
                instant(rs.getTimestamp("free_started_at")), instant(rs.getTimestamp("free_ends_at")),
                instant(rs.getTimestamp("paid_started_at")), instant(rs.getTimestamp("ended_at")),
                rs.getInt("billable_seconds"), rs.getInt("lock_version"),
                rs.getObject("video_room_id", UUID.class), rs.getString("video_status"),
                rs.getTimestamp("join_deadline").toInstant(), instant(rs.getTimestamp("paid_acceptance_deadline")),
                rs.getBoolean("persistent_recording_enabled")), sessionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");
        return rows.getFirst();
    }

    private AppointmentRow lockAppointment(UUID appointmentId) {
        var rows = jdbc.query("""
                select id, auction_id, host_user_id, bidder_user_id, status::text
                  from appointment.appointment where id = ? for update
                """, (rs, n) -> new AppointmentRow(rs.getObject("id", UUID.class), rs.getObject("auction_id", UUID.class),
                rs.getObject("host_user_id", UUID.class), rs.getObject("bidder_user_id", UUID.class), rs.getString("status")), appointmentId);
        if (rows.isEmpty()) throw ApiProblem.notFound("APPOINTMENT_NOT_FOUND", "Appointment no existe.");
        return rows.getFirst();
    }

    private SessionContext lockSessionContext(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id, s.appointment_id, s.status::text session_status, s.free_ends_at, s.paid_started_at,
                       a.host_user_id, a.bidder_user_id, a.duration_minutes, a.agreed_amount_clp,
                       vr.id video_room_id, vr.provider_room_ref, vr.join_deadline, vr.free_online_end,
                       coalesce(vr.provider_region, 'MOCK') provider
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  join media.video_room vr on vr.appointment_id = a.id
                 where s.id = ? for update of s
                """, (rs, n) -> new SessionContext(
                rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class), rs.getString("session_status"),
                rs.getObject("host_user_id", UUID.class), rs.getObject("bidder_user_id", UUID.class),
                rs.getInt("duration_minutes"), rs.getLong("agreed_amount_clp"),
                rs.getObject("video_room_id", UUID.class), rs.getString("provider"), rs.getString("provider_room_ref"),
                rs.getTimestamp("join_deadline").toInstant(), instant(rs.getTimestamp("free_ends_at")),
                instant(rs.getTimestamp("paid_started_at"))), sessionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");
        return rows.getFirst();
    }

    private String participantRole(UUID actor, SessionContext ctx) {
        if (actor.equals(ctx.hostUserId())) return "HOST";
        if (actor.equals(ctx.bidderUserId())) return "BIDDER";
        throw ApiProblem.forbidden("SESSION_PARTICIPANT_REQUIRED", "Actor no participa en esta sesión.");
    }

    private UUID existingSessionId(UUID appointmentId) {
        UUID id = jdbc.query("select id from appointment.appointment_session where appointment_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, appointmentId);
        if (id == null) throw new IllegalStateException("Confirmed appointment has no session");
        return id;
    }

    private UUID existingRoomId(UUID appointmentId) {
        UUID id = jdbc.query("select id from media.video_room where appointment_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, appointmentId);
        if (id == null) throw new IllegalStateException("Confirmed appointment has no video room");
        return id;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private record AppointmentRow(UUID id, UUID auctionId, UUID hostUserId, UUID bidderUserId, String status) {}
    private record SessionContext(UUID id, UUID appointmentId, String sessionStatus, UUID hostUserId, UUID bidderUserId,
                                  int durationMinutes, long agreedAmountClp, UUID videoRoomId, String provider,
                                  String providerRoomRef, Instant joinDeadline, Instant freeEndsAt, Instant paidStartedAt) {}

    public record ConfirmResult(UUID appointmentId, UUID sessionId, UUID videoRoomId, String status, boolean replayed) {}
    public record JoinRequest(boolean cameraValid, boolean mediaFlowing, boolean audioMuted) {}
    public record TurnView(java.util.List<String> urls, String username, String credential, Instant expiresAt) {}
    public record JoinResult(UUID sessionId, UUID videoRoomId, String participantRole, int readyParticipants,
                             String sessionStatus, Instant freeEndsAt, TurnView turn) {}
    public record FinishResult(SessionView session, int billableSeconds,
                               boolean proportionalSettlementNeedsRoundingPolicy, String settlementState) {}
    public record SessionView(UUID id, UUID appointmentId, UUID hostUserId, UUID bidderUserId, String status,
                              int durationMinutes, long agreedAmountClp, Instant freeStartedAt, Instant freeEndsAt,
                              Instant paidStartedAt, Instant endedAt, int billableSeconds, int lockVersion,
                              UUID videoRoomId, String videoStatus, Instant joinDeadline,
                              Instant paidAcceptanceDeadline, boolean persistentRecordingEnabled) {}
}

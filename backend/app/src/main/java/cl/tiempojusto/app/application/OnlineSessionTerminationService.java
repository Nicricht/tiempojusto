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
import java.util.Map;
import java.util.UUID;

@Service
public class OnlineSessionTerminationService {
    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;
    private final WebRtcPort webRtc;
    private final OnlineReconnectApplicationService reconnect;

    public OnlineSessionTerminationService(JdbcTemplate jdbc,
                                           ApplicationPersistenceSupport persistence,
                                           WebRtcPort webRtc,
                                           OnlineReconnectApplicationService reconnect) {
        this.jdbc = jdbc;
        this.persistence = persistence;
        this.webRtc = webRtc;
        this.reconnect = reconnect;
    }

    @Transactional
    public FinishResult finish(UUID actorUserId, UUID sessionId) {
        SessionContext ctx = lockContext(sessionId);
        participantRole(actorUserId, ctx);
        if ("ENDED".equals(ctx.status())) {
            int billable = currentBillable(sessionId, ctx.durationMinutes());
            String settlement = settlementState(ctx.agreedAmountClp(), ctx.durationMinutes(), billable);
            return new FinishResult(reconnect.get(sessionId), billable,
                    "PENDING_ROUNDING_POLICY".equals(settlement), settlement, true);
        }

        Instant now = Instant.now();
        if ("PAID_ACTIVE".equals(ctx.status())) {
            Instant paidSegmentStartedAt = openSegmentStartedAt(sessionId, "PAID");
            if (paidSegmentStartedAt != null) {
                int seconds = safeSeconds(Duration.between(paidSegmentStartedAt, now));
                jdbc.update("""
                        update appointment.session_segment
                           set ended_at = ?, billable_seconds = ?
                         where session_id = ? and segment_type = 'PAID' and ended_at is null
                        """, Timestamp.from(now), seconds, sessionId);
            }
        }
        jdbc.update("""
                update appointment.session_segment
                   set ended_at = ?
                 where session_id = ? and ended_at is null
                """, Timestamp.from(now), sessionId);

        int billable = currentBillable(sessionId, ctx.durationMinutes());
        String settlement = settlementState(ctx.agreedAmountClp(), ctx.durationMinutes(), billable);

        jdbc.update("""
                update appointment.appointment_session
                   set status = 'ENDED', ended_at = ?, billable_seconds = ?, lock_version = lock_version + 1
                 where id = ?
                """, Timestamp.from(now), billable, sessionId);
        jdbc.update("""
                update appointment.appointment
                   set status = 'ENDED', lock_version = lock_version + 1
                 where id = ?
                """, ctx.appointmentId());
        jdbc.update("update media.video_room set status = 'ENDED' where id = ?", ctx.videoRoomId());

        try {
            webRtc.closeRoom(new RoomHandle(ctx.provider(), ctx.providerRoomRef(), RoomKind.ONLINE_PRIVATE, false), now);
        } catch (RuntimeException ignored) {
            // Database lifecycle remains authoritative if the media provider is unavailable.
        }

        persistence.audit(actorUserId, "SESSION_FINISHED", "SESSION", sessionId,
                Map.of("billableSeconds", billable, "settlementState", settlement,
                        "source", "PERSISTED_PAID_SEGMENTS"));
        persistence.outbox("SESSION", sessionId, "SESSION_FINISHED",
                Map.of("sessionId", sessionId, "billableSeconds", billable,
                        "settlementState", settlement));

        return new FinishResult(reconnect.get(sessionId), billable,
                "PENDING_ROUNDING_POLICY".equals(settlement), settlement, false);
    }

    private SessionContext lockContext(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id, s.appointment_id, s.status::text,
                       a.host_user_id, a.bidder_user_id, a.duration_minutes, a.agreed_amount_clp,
                       vr.id video_room_id, vr.provider_room_ref,
                       coalesce(vr.provider_region, 'MOCK') provider
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  join media.video_room vr on vr.appointment_id = a.id
                 where s.id = ?
                 for update of s, vr
                """, (rs, n) -> new SessionContext(
                rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class),
                rs.getString("status"), rs.getObject("host_user_id", UUID.class),
                rs.getObject("bidder_user_id", UUID.class), rs.getInt("duration_minutes"),
                rs.getLong("agreed_amount_clp"), rs.getObject("video_room_id", UUID.class),
                rs.getString("provider"), rs.getString("provider_room_ref")), sessionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");
        return rows.getFirst();
    }

    private void participantRole(UUID actor, SessionContext ctx) {
        if (actor.equals(ctx.hostUserId()) || actor.equals(ctx.bidderUserId())) return;
        throw ApiProblem.forbidden("SESSION_PARTICIPANT_REQUIRED", "Actor no participa en esta sesión.");
    }

    private Instant openSegmentStartedAt(UUID sessionId, String type) {
        Timestamp ts = jdbc.query("""
                select started_at from appointment.session_segment
                 where session_id = ? and segment_type::text = ? and ended_at is null
                 order by id desc limit 1
                """, rs -> rs.next() ? rs.getTimestamp(1) : null, sessionId, type);
        return ts == null ? null : ts.toInstant();
    }

    private int currentBillable(UUID sessionId, int durationMinutes) {
        Long total = jdbc.queryForObject("""
                select coalesce(sum(billable_seconds), 0)
                  from appointment.session_segment
                 where session_id = ? and segment_type = 'PAID'
                """, Long.class, sessionId);
        long cap = (long) durationMinutes * 60L;
        return (int) Math.min(cap, total == null ? 0L : total);
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

    private record SessionContext(UUID id, UUID appointmentId, String status,
                                  UUID hostUserId, UUID bidderUserId, int durationMinutes,
                                  long agreedAmountClp, UUID videoRoomId, String provider,
                                  String providerRoomRef) {}

    public record FinishResult(OnlineReconnectApplicationService.StateView session,
                               int billableSeconds,
                               boolean proportionalSettlementNeedsRoundingPolicy,
                               String settlementState,
                               boolean replayed) {}
}

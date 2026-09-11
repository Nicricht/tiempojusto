package cl.tiempojusto.app.goldenpath;

import cl.tiempojusto.app.application.AuctionApplicationService;
import cl.tiempojusto.app.application.IdentityApplicationService;
import cl.tiempojusto.app.application.OnlineApplicationService;
import cl.tiempojusto.app.application.OnlineReconnectApplicationService;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("ci")
public class ReconnectGoldenPathService {
    private final IdentityApplicationService identity;
    private final AuctionApplicationService auctions;
    private final OnlineApplicationService online;
    private final OnlineReconnectApplicationService reconnect;
    private final MockPaymentPort payments;
    private final JdbcTemplate jdbc;

    public ReconnectGoldenPathService(IdentityApplicationService identity,
                                      AuctionApplicationService auctions,
                                      OnlineApplicationService online,
                                      OnlineReconnectApplicationService reconnect,
                                      MockPaymentPort payments,
                                      JdbcTemplate jdbc) {
        this.identity = identity;
        this.auctions = auctions;
        this.online = online;
        this.reconnect = reconnect;
        this.payments = payments;
        this.jdbc = jdbc;
    }

    public Map<String, Object> run() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var host = identity.registerSandbox(new IdentityApplicationService.RegistrationRequest(
                "HOST", "host-reconnect-" + suffix + "@tiempojusto.test",
                "host_reconnect_" + suffix, "Host Reconnect", 28, true));
        identity.verifySandboxAdult(host.userId(), new IdentityApplicationService.VerificationRequest(
                LocalDate.of(1998, 4, 12), "CL"));

        var bidder = identity.registerSandbox(new IdentityApplicationService.RegistrationRequest(
                "BIDDER", "bidder-reconnect-" + suffix + "@tiempojusto.test",
                "bidder_reconnect_" + suffix, "Bidder Reconnect", 30, false));
        identity.verifySandboxAdult(bidder.userId(), new IdentityApplicationService.VerificationRequest(
                LocalDate.of(1996, 2, 20), "CL"));
        payments.setAvailableFunds(bidder.userId(), 120_000L);

        var auction = auctions.openSandbox(host.userId(), new AuctionApplicationService.OpenRequest(
                host.hostProfileId(), 30, 45_000L, 60_000L));
        var close = auctions.closeNow(bidder.userId(), auction.id(), "ci-reconnect-" + suffix);
        var confirmed = online.confirmWinner(bidder.userId(), close.appointmentId());
        UUID sessionId = confirmed.sessionId();
        UUID roomId = confirmed.videoRoomId();

        var mediaOk = new OnlineApplicationService.JoinRequest(true, true, false);
        online.join(host.userId(), sessionId, mediaOk);
        online.join(bidder.userId(), sessionId, mediaOk);
        jdbc.update("""
                update appointment.appointment_session
                   set free_started_at = clock_timestamp() - interval '2 minutes',
                       free_ends_at = clock_timestamp()
                 where id = ?
                """, sessionId);
        jdbc.update("""
                update media.video_room vr
                   set free_online_end = s.free_ends_at
                  from appointment.appointment_session s
                 where s.id = ? and vr.appointment_id = s.appointment_id
                """, sessionId);
        online.acceptPaid(host.userId(), sessionId);
        online.acceptPaid(bidder.userId(), sessionId);

        jdbc.update("""
                update appointment.session_segment
                   set started_at = clock_timestamp() - interval '20 seconds'
                 where session_id = ? and segment_type = 'PAID' and ended_at is null
                """, sessionId);
        setBothMediaBackdated(roomId, 4);

        var invalid = new OnlineReconnectApplicationService.MediaSignalRequest(false, false, false);
        var fourSecondSignal = reconnect.signal(bidder.userId(), sessionId, invalid);
        require("PAID_ACTIVE".equals(fourSecondSignal.sessionStatus()),
                "A 4-second microcut must remain PAID_ACTIVE");

        setBothMediaBackdated(roomId, 6);
        var interrupted = reconnect.signal(bidder.userId(), sessionId, invalid);
        require("RECONNECTING".equals(interrupted.sessionStatus()),
                "A confirmed interruption beyond 5 seconds must enter RECONNECTING");
        require(interrupted.reconnectDeadline() != null, "Reconnect deadline is required");

        reconnect.signal(host.userId(), sessionId,
                new OnlineReconnectApplicationService.MediaSignalRequest(true, true, true));
        var recovered = reconnect.signal(bidder.userId(), sessionId,
                new OnlineReconnectApplicationService.MediaSignalRequest(true, true, false));
        require("RECONNECTING".equals(recovered.sessionStatus()),
                "Technical recovery alone must not resume billing");
        require(recovered.recoveredAt() != null, "Recovery timestamp is required");

        var oneAccepted = reconnect.acceptResume(host.userId(), sessionId);
        require("RECONNECTING".equals(oneAccepted.sessionStatus()),
                "One resume acceptance is insufficient");
        var resumed = reconnect.acceptResume(bidder.userId(), sessionId);
        require("PAID_ACTIVE".equals(resumed.sessionStatus()),
                "Bilateral resume must return to PAID_ACTIVE");

        setBothMediaBackdated(roomId, 6);
        var interruptedAgain = reconnect.signal(bidder.userId(), sessionId, invalid);
        require("RECONNECTING".equals(interruptedAgain.sessionStatus()),
                "Second interruption must enter RECONNECTING");

        jdbc.update("""
                update media.video_room
                   set media_lost_at = clock_timestamp() - interval '2 minutes',
                       reconnect_deadline = clock_timestamp()
                 where id = ?
                """, roomId);
        var timedOut = reconnect.evaluate(sessionId);
        require("ENDED".equals(timedOut.sessionStatus()),
                "Expired reconnect window must end the session");

        int incidentCount = intQuery("select count(*) from media.media_incident where video_room_id = ? and incident_type = 'ONLINE_MEDIA_INTERRUPTION'", roomId);
        int reconnectSegments = intQuery("select count(*) from appointment.session_segment where session_id = ? and segment_type = 'RECONNECT' and billable = false and ended_at is not null", sessionId);
        int paidSegments = intQuery("select count(*) from appointment.session_segment where session_id = ? and segment_type = 'PAID'", sessionId);
        int segmentBillable = intQuery("select coalesce(sum(billable_seconds),0)::int from appointment.session_segment where session_id = ? and segment_type = 'PAID'", sessionId);
        int persistedBillable = intQuery("select billable_seconds from appointment.appointment_session where id = ?", sessionId);
        int finishEvent = intQuery("select count(*) from platform.outbox_event where aggregate_id = ? and event_type = 'SESSION_FINISHED' and payload->>'reason' = 'FINISHED_RECONNECT_TIMEOUT'", sessionId);
        boolean recordingDisabled = Boolean.TRUE.equals(jdbc.queryForObject(
                "select not persistent_recording_enabled from media.video_room where id = ?", Boolean.class, roomId));

        require(incidentCount == 2, "Two media incidents expected");
        require(reconnectSegments == 2, "Two closed non-billable reconnect segments expected");
        require(paidSegments == 2, "Two paid segments expected after one successful resume");
        require(persistedBillable == segmentBillable, "Session billing must equal persisted PAID segments");
        require(persistedBillable > 0 && persistedBillable < 30, "Reconnect time must not inflate billable seconds");
        require(finishEvent == 1, "Reconnect timeout must emit terminal outbox event");
        require(recordingDisabled, "Private Online recording must remain disabled");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS");
        result.put("sessionId", sessionId);
        result.put("microCut4sState", fourSecondSignal.sessionStatus());
        result.put("interruption6sState", interrupted.sessionStatus());
        result.put("recoveredWithoutBillingResumeState", recovered.sessionStatus());
        result.put("bilateralResumeState", resumed.sessionStatus());
        result.put("timeoutState", timedOut.sessionStatus());
        result.put("incidentCount", incidentCount);
        result.put("reconnectSegments", reconnectSegments);
        result.put("paidSegments", paidSegments);
        result.put("billableSeconds", persistedBillable);
        result.put("persistentRecordingEnabled", false);
        return result;
    }

    private void setBothMediaBackdated(UUID roomId, int seconds) {
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(seconds));
        jdbc.update("""
                update media.video_participant_state
                   set camera_valid = true,
                       media_flowing = true,
                       last_valid_media_at = ?,
                       last_heartbeat_at = ?,
                       updated_at = clock_timestamp()
                 where video_room_id = ?
                """, at, at, roomId);
    }

    private int intQuery(String sql, Object arg) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arg);
        return value == null ? 0 : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

package cl.tiempojusto.app.goldenpath;

import cl.tiempojusto.app.application.AuctionApplicationService;
import cl.tiempojusto.app.application.FinanceReadService;
import cl.tiempojusto.app.application.IdentityApplicationService;
import cl.tiempojusto.app.application.OnlineApplicationService;
import cl.tiempojusto.app.application.OnlineSessionTerminationService;
import cl.tiempojusto.app.application.PayoutAvailabilityApplicationService;
import cl.tiempojusto.app.application.PersistentSettlementApplicationService;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("ci")
public class PersistentSettlementGoldenPathService {
    private final IdentityApplicationService identity;
    private final AuctionApplicationService auctions;
    private final OnlineApplicationService online;
    private final OnlineSessionTerminationService termination;
    private final PersistentSettlementApplicationService settlements;
    private final PayoutAvailabilityApplicationService payouts;
    private final FinanceReadService financeRead;
    private final MockPaymentPort payments;
    private final JdbcTemplate jdbc;

    public PersistentSettlementGoldenPathService(IdentityApplicationService identity,
                                                 AuctionApplicationService auctions,
                                                 OnlineApplicationService online,
                                                 OnlineSessionTerminationService termination,
                                                 PersistentSettlementApplicationService settlements,
                                                 PayoutAvailabilityApplicationService payouts,
                                                 FinanceReadService financeRead,
                                                 MockPaymentPort payments,
                                                 JdbcTemplate jdbc) {
        this.identity = identity;
        this.auctions = auctions;
        this.online = online;
        this.termination = termination;
        this.settlements = settlements;
        this.payouts = payouts;
        this.financeRead = financeRead;
        this.payments = payments;
        this.jdbc = jdbc;
    }

    public Map<String, Object> run() {
        String root = UUID.randomUUID().toString().substring(0, 8);

        Fixture full = paidFixture(root + "f", 15, 60_000L, 900);
        var finish = termination.finish(full.bidderUserId(), full.sessionId());
        require("READY_FOR_SETTLEMENT".equals(finish.settlementState()),
                "Full paid duration must be ready for settlement");
        var posted = settlements.processSession(full.sessionId());
        require("POSTED".equals(posted.status()), "Full session settlement must POST");
        require(Long.valueOf(60_000L).equals(posted.generatedAmountClp()), "Gross must be 60000 CLP");
        require(Long.valueOf(48_000L).equals(posted.hostAmountClp()), "HOST share must be 80% = 48000 CLP");
        require(Long.valueOf(12_000L).equals(posted.platformAmountClp()), "Platform share must be 20% = 12000 CLP");
        require(posted.payoutId() != null && "PENDING_HOLD".equals(posted.payoutStatus()),
                "Payout must begin in 60-minute pending hold");
        require(ledgerBalanced(posted.ledgerTransactionId()), "Session settlement ledger transaction must balance");

        var objectiveHold = payouts.addObjectiveHold(
                posted.payoutId(), null, "CI_OBJECTIVE_INCIDENT", "ci:evidence:" + root);
        backdatePayoutHold(posted.payoutId(), posted.ledgerTransactionId());
        var stillPending = payouts.releaseIfEligible(posted.payoutId());
        require("PENDING_HOLD".equals(stillPending.status()),
                "Active objective incident must prevent automatic payout availability");
        payouts.releaseObjectiveHold(objectiveHold.id());
        var available = payouts.releaseIfEligible(posted.payoutId());
        require("AVAILABLE".equals(available.status()), "Released hold must allow payout availability");
        require(available.availabilityTransactionId() != null
                        && ledgerBalanced(available.availabilityTransactionId()),
                "Payout hold-release ledger transaction must balance");

        var balance = financeRead.balance(full.hostUserId());
        require(balance.pendingClp() == 0L, "HOST pending balance must be zero after availability release");
        require(balance.availableClp() == 48_000L, "HOST available balance must be 48000 CLP");
        require(balance.heldForReviewClp() == 0L, "Released objective hold must no longer count as held");

        Fixture zero = freeFixture(root + "z", 15, 60_000L);
        var zeroFinish = termination.finish(zero.bidderUserId(), zero.sessionId());
        require(zeroFinish.billableSeconds() == 0, "Free-only finish must bill zero seconds");
        var zeroSettlement = settlements.processSession(zero.sessionId());
        require("ZERO_BILLING".equals(zeroSettlement.status()), "Zero billing must not create money movement");
        require(zeroSettlement.ledgerTransactionId() == null && zeroSettlement.payoutId() == null,
                "Zero billing must not create ledger settlement or payout");
        require("RELEASED".equals(fundsStatus(zero.sessionId())),
                "Zero billing must release the complete bidder reservation");

        Fixture fractional = paidFixture(root + "r", 15, 60_000L, 1);
        termination.finish(fractional.bidderUserId(), fractional.sessionId());
        forceOneBillableSecond(fractional.sessionId());
        var blocked = settlements.processSession(fractional.sessionId());
        require("PENDING_ROUNDING_POLICY".equals(blocked.status()),
                "Persistent finance must retain pending rounding policy state");
        require("PROPORTIONAL_CLP_FRACTION".equals(blocked.roundingReason()),
                "One billable second at 60000/15m must expose the proportional CLP fraction");
        require(blocked.ledgerTransactionId() == null && blocked.payoutId() == null,
                "Rounding-policy block must not move money");
        require("RESERVED".equals(fundsStatus(fractional.sessionId())),
                "Rounding-policy block must keep the reservation untouched");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS");
        result.put("postedSessionId", full.sessionId());
        result.put("grossAmountClp", posted.generatedAmountClp());
        result.put("hostAmountClp", posted.hostAmountClp());
        result.put("platformAmountClp", posted.platformAmountClp());
        result.put("payoutStatus", available.status());
        result.put("hostAvailableClp", balance.availableClp());
        result.put("objectiveHoldBlockedRelease", true);
        result.put("zeroBillingStatus", zeroSettlement.status());
        result.put("roundingPolicyStatus", blocked.status());
        result.put("roundingPolicyReason", blocked.roundingReason());
        return result;
    }

    private Fixture paidFixture(String suffix, int durationMinutes, long closeNowAmountClp, int paidSeconds) {
        Fixture fixture = freeFixture(suffix, durationMinutes, closeNowAmountClp);
        jdbc.update("""
                update appointment.appointment_session
                   set free_started_at = clock_timestamp() - interval '2 minutes',
                       free_ends_at = clock_timestamp()
                 where id = ?
                """, fixture.sessionId());
        jdbc.update("""
                update media.video_room vr
                   set free_online_end = s.free_ends_at
                  from appointment.appointment_session s
                 where s.id = ? and vr.appointment_id = s.appointment_id
                """, fixture.sessionId());
        online.acceptPaid(fixture.hostUserId(), fixture.sessionId());
        online.acceptPaid(fixture.bidderUserId(), fixture.sessionId());
        jdbc.update("""
                update appointment.session_segment
                   set started_at = clock_timestamp() - (? * interval '1 second')
                 where session_id = ? and segment_type = 'PAID' and ended_at is null
                """, paidSeconds, fixture.sessionId());
        return fixture;
    }

    private Fixture freeFixture(String suffix, int durationMinutes, long closeNowAmountClp) {
        var host = identity.registerSandbox(new IdentityApplicationService.RegistrationRequest(
                "HOST", "host-settle-" + suffix + "@tiempojusto.test",
                "host_settle_" + suffix, "Host Settlement", 28, true));
        identity.verifySandboxAdult(host.userId(), new IdentityApplicationService.VerificationRequest(
                LocalDate.of(1998, 4, 12), "CL"));

        var bidder = identity.registerSandbox(new IdentityApplicationService.RegistrationRequest(
                "BIDDER", "bidder-settle-" + suffix + "@tiempojusto.test",
                "bidder_settle_" + suffix, "Bidder Settlement", 30, false));
        identity.verifySandboxAdult(bidder.userId(), new IdentityApplicationService.VerificationRequest(
                LocalDate.of(1996, 2, 20), "CL"));
        payments.setAvailableFunds(bidder.userId(), 200_000L);

        var auction = auctions.openSandbox(host.userId(), new AuctionApplicationService.OpenRequest(
                host.hostProfileId(), durationMinutes, 45_000L, closeNowAmountClp));
        var close = auctions.closeNow(bidder.userId(), auction.id(), "ci-settlement-" + suffix);
        var confirmed = online.confirmWinner(bidder.userId(), close.appointmentId());
        var mediaOk = new OnlineApplicationService.JoinRequest(true, true, false);
        online.join(host.userId(), confirmed.sessionId(), mediaOk);
        online.join(bidder.userId(), confirmed.sessionId(), mediaOk);
        return new Fixture(host.userId(), bidder.userId(), confirmed.sessionId());
    }

    private void forceOneBillableSecond(UUID sessionId) {
        jdbc.update("""
                update appointment.session_segment
                   set billable_seconds = 1
                 where session_id = ? and segment_type = 'PAID'
                """, sessionId);
        jdbc.update("""
                update appointment.appointment_session
                   set billable_seconds = 1
                 where id = ?
                """, sessionId);
    }

    private void backdatePayoutHold(UUID payoutId, UUID sourceTransactionId) {
        jdbc.update("""
                update finance.ledger_transaction
                   set posted_at = clock_timestamp() - interval '61 minutes'
                 where id = ?
                """, sourceTransactionId);
        jdbc.update("""
                update finance.payout
                   set pending_until = (
                       select posted_at + interval '60 minutes'
                         from finance.ledger_transaction where id = ?
                   )
                 where id = ?
                """, sourceTransactionId, payoutId);
    }

    private boolean ledgerBalanced(UUID transactionId) {
        Boolean balanced = jdbc.queryForObject("""
                select coalesce(sum(amount_clp) filter (where direction = 'DEBIT'), 0)
                     = coalesce(sum(amount_clp) filter (where direction = 'CREDIT'), 0)
                   and count(*) >= 2
                  from finance.ledger_entry
                 where transaction_id = ?
                """, Boolean.class, transactionId);
        return Boolean.TRUE.equals(balanced);
    }

    private String fundsStatus(UUID sessionId) {
        return jdbc.queryForObject("""
                select fr.status::text
                  from appointment.appointment_session s
                  join appointment.appointment ap on ap.id = s.appointment_id
                  join auction.auction a on a.id = ap.auction_id
                  join auction.bid b on b.id = a.winning_bid_id
                  join auction.funds_reservation fr on fr.id = b.funds_reservation_id
                 where s.id = ?
                """, String.class, sessionId);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(UUID hostUserId, UUID bidderUserId, UUID sessionId) {}
}

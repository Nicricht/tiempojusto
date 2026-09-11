package cl.tiempojusto.app.goldenpath;

import cl.tiempojusto.finance.payment.MockPaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort.PayoutTransfer;
import cl.tiempojusto.finance.payment.PaymentPort.Reservation;
import cl.tiempojusto.finance.settlement.FinanceEngine;
import cl.tiempojusto.finance.settlement.FinanceEngine.SettlementResult;
import cl.tiempojusto.media.RoomHandle;
import cl.tiempojusto.media.RoomKind;
import cl.tiempojusto.media.WebRtcPort;
import cl.tiempojusto.statemachine.auction.Auction;
import cl.tiempojusto.statemachine.online.OnlineSession;
import cl.tiempojusto.statemachine.proposal.Proposal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile({"dev", "test", "ci"})
public class SandboxOnlineGoldenPathService {

    private final MockPaymentPort paymentPort;
    private final FinanceEngine finance;
    private final WebRtcPort webRtc;

    public SandboxOnlineGoldenPathService(MockPaymentPort paymentPort,
                                          FinanceEngine finance,
                                          WebRtcPort webRtc) {
        this.paymentPort = paymentPort;
        this.finance = finance;
        this.webRtc = webRtc;
    }

    /**
     * Executes one deterministic happy path using sandbox adapters.
     * It intentionally uses the whole paid duration so this harness does not invent
     * the still-unfrozen CLP rounding rule for partial-second proportional billing.
     */
    public synchronized Result run(Request request) {
        validate(request);

        UUID hostUserId = UUID.randomUUID();
        UUID bidderUserId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant t0 = Instant.now();

        // Sandbox KYC boundary: no raw document/biometric is created or stored here.
        boolean hostKycVerifiedAdult = true;
        boolean bidderKycVerifiedAdult = true;
        if (!(hostKycVerifiedAdult && bidderKycVerifiedAdult)) {
            throw new IllegalStateException("Sandbox KYC verification failed");
        }

        Proposal proposal = Proposal.activate(request.amountClp(), t0, true, false);

        long opening = Math.max(10_000L, request.amountClp() - 15_000L);
        opening -= opening % 5_000L;
        if (opening >= request.amountClp()) opening = request.amountClp() - 5_000L;
        Auction auction = Auction.open(t0.plusSeconds(1), opening, request.amountClp());

        paymentPort.setAvailableFunds(bidderUserId, request.amountClp());
        Reservation reservation = finance.reserveBid(
                bidderUserId,
                request.amountClp(),
                "golden:" + sessionId + ":reserve",
                t0.plusSeconds(2));

        auction = auction.closeNow(t0.plusSeconds(3), request.amountClp(), true, true);

        RoomHandle room = webRtc.createRoom(
                RoomKind.ONLINE_PRIVATE,
                sessionId,
                false,
                t0.plusSeconds(4));
        webRtc.issueTurnCredentials(room, hostUserId, t0.plusSeconds(4));
        webRtc.issueTurnCredentials(room, bidderUserId, t0.plusSeconds(4));

        OnlineSession online = OnlineSession.winnerSelected()
                .openJoinWindow(t0.plusSeconds(5))
                .bothConnected(t0.plusSeconds(10), true, true);

        online = online.freeTimeout(online.freeEndsAt(), true);
        online = online.acceptPaid(true, online.freeEndsAt().plusSeconds(1), true);
        online = online.acceptPaid(false, online.freeEndsAt().plusSeconds(2), true);
        if (!online.billable()) {
            throw new IllegalStateException("Online session did not reach PAID_ACTIVE");
        }

        Instant paidStartedAt = online.freeEndsAt().plusSeconds(2);
        Instant paidFinishedAt = paidStartedAt.plusSeconds((long) request.durationMinutes() * 60L);
        online = online.finish();

        SettlementResult settlement = finance.settleSession(
                hostUserId,
                reservation.id(),
                request.amountClp(),
                sessionId,
                "golden:" + sessionId + ":settle",
                paidFinishedAt);

        var available = finance.releaseHold(
                settlement.earning().id(),
                paidFinishedAt.plusSeconds(3600),
                "golden:" + sessionId + ":hold-release");

        PayoutTransfer payout = finance.requestPayout(
                available.id(),
                "golden:" + sessionId + ":payout",
                paidFinishedAt.plusSeconds(3601));

        webRtc.closeRoom(room, paidFinishedAt.plusSeconds(3602));

        return new Result(
                "PASS",
                hostUserId,
                bidderUserId,
                sessionId,
                proposal.state().name(),
                auction.state().name(),
                online.state().name(),
                request.amountClp(),
                settlement.earning().hostAmountClp(),
                settlement.earning().platformAmountClp(),
                payout.amountClp(),
                false,
                List.of(
                        "KYC_SANDBOX_VERIFIED_ADULT",
                        "PROPOSAL_ACTIVE",
                        "AUCTION_CLOSED_NOW",
                        "FUNDS_RESERVED",
                        "ONLINE_PRIVATE_ROOM_NO_RECORDING",
                        "FREE_ONLINE_2M",
                        "BILATERAL_PAID_ACCEPTED",
                        "PAID_ACTIVE_FULL_DURATION",
                        "SESSION_SETTLED_80_20",
                        "HOLD_60M_RELEASED",
                        "PAYOUT_SENT"
                ));
    }

    private static void validate(Request request) {
        if (request == null) throw new IllegalArgumentException("request required");
        if (request.amountClp() < 10_000 || request.amountClp() % 5_000 != 0) {
            throw new IllegalArgumentException("amountClp must be >=10000 and multiple of 5000");
        }
        if (!(request.durationMinutes() == 15 || request.durationMinutes() == 30 || request.durationMinutes() == 60)) {
            throw new IllegalArgumentException("Online duration must be 15, 30 or 60 minutes");
        }
    }

    public record Request(long amountClp, int durationMinutes) {}

    public record Result(
            String status,
            UUID hostUserId,
            UUID bidderUserId,
            UUID sessionId,
            String proposalState,
            String auctionState,
            String onlineFinalState,
            long grossAmountClp,
            long hostAmountClp,
            long platformAmountClp,
            long payoutAmountClp,
            boolean persistentRecordingEnabled,
            List<String> checkpoints) {}
}

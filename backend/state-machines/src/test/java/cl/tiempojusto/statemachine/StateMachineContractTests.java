package cl.tiempojusto.statemachine;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;
import cl.tiempojusto.statemachine.proposal.*;
import cl.tiempojusto.statemachine.meta.*;
import cl.tiempojusto.statemachine.auction.*;
import cl.tiempojusto.statemachine.winner.*;
import cl.tiempojusto.statemachine.presential.*;
import cl.tiempojusto.statemachine.online.*;
import cl.tiempojusto.statemachine.extension.*;
import cl.tiempojusto.statemachine.live.*;
import cl.tiempojusto.statemachine.payout.*;
import cl.tiempojusto.statemachine.safety.*;
import cl.tiempojusto.statemachine.noshow.*;

public final class StateMachineContractTests {
    private static int passed = 0;
    private static final Instant T0 = Instant.parse("2026-08-14T12:00:00Z");

    public static void main(String[] args) {
        test("Proposal lifecycle", StateMachineContractTests::proposalLifecycle);
        test("Proposal amount guard", StateMachineContractTests::proposalAmountGuard);
        test("Meta triggers from highest compatible Proposal", StateMachineContractTests::metaTrigger);
        test("Meta cancellation guard after 100%", StateMachineContractTests::metaCancelGuard);
        test("Auction base 15 minutes", StateMachineContractTests::auctionBaseDuration);
        test("Auction anti-sniping resets to exactly 2:00", StateMachineContractTests::antiSniping);
        test("Funding failure never extends auction", StateMachineContractTests::fundingFailureNoExtension);
        test("Close Now closes immediately", StateMachineContractTests::closeNow);
        test("Winner confirmation within 3 minutes", StateMachineContractTests::winnerConfirm);
        test("Winner timeout penalty and backups", StateMachineContractTests::winnerBackups);
        test("Presential ETA max 30 minutes", StateMachineContractTests::etaGuard);
        test("Arrival creates handshake window but no billing", StateMachineContractTests::arrival);
        test("Handshake bilateral and single +5 extension", StateMachineContractTests::handshake);
        test("Presential free period is 5 minutes", StateMachineContractTests::presentialFree);
        test("Presential pause and bilateral resume", StateMachineContractTests::presentialPauseResume);
        test("Online join window 3 minutes", StateMachineContractTests::onlineJoin);
        test("Online free 2 minutes then bilateral paid consent", StateMachineContractTests::onlinePaidConsent);
        test("Online paid consent timeout finishes free", StateMachineContractTests::onlineConsentTimeout);
        test("Online microcut tolerance 5 seconds", StateMachineContractTests::onlineMicrocut);
        test("Online reconnect requires bilateral acceptance", StateMachineContractTests::onlineReconnect);
        test("Online reconnect timeout ends session", StateMachineContractTests::onlineReconnectTimeout);
        test("Extension +15/+30 and max 3 rounds", StateMachineContractTests::extensionRounds);
        test("Extension fails without funds", StateMachineContractTests::extensionFunds);
        test("Bidder no-show escalation", StateMachineContractTests::bidderNoShow);
        test("Host no-show escalation", StateMachineContractTests::hostNoShow);
        test("Live requires >=5 minutes remaining", StateMachineContractTests::liveStartGuard);
        test("Live ticket pricing and cutoff", StateMachineContractTests::ticketPricing);
        test("Live reconnect 2 minutes", StateMachineContractTests::liveReconnect);
        test("Payout 60 minute hold", StateMachineContractTests::payoutHold);
        test("Payout objective incident creates review hold", StateMachineContractTests::payoutIncident);
        test("Safety S5 requires human review", StateMachineContractTests::s5HumanReview);
        test("Safety undetermined assigns no culpability", StateMachineContractTests::safetyUndetermined);
        test("Appeal window 7 days", StateMachineContractTests::appealWindow);
        test("Appeal can reduce or revoke", StateMachineContractTests::appealOutcomes);
        System.out.println("\nPASS: " + passed + " contract tests");
    }

    private static void proposalLifecycle() {
        Proposal p = Proposal.activate(80_000, T0, true, false);
        eq(ProposalState.ACTIVE, p.state()); isTrue(p.countsInMetrics());
        p = p.update(100_000, T0.plusSeconds(60)); eq(ProposalState.UPDATED, p.state());
        p = p.normalizeUpdated(); eq(ProposalState.ACTIVE, p.state());
        p = p.withdraw(T0.plusSeconds(120), false); eq(ProposalState.WITHDRAWN, p.state());
        eq(T0.plusSeconds(120 + 24*3600), p.cooldownUntil()); isFalse(p.countsInMetrics());
    }
    private static void proposalAmountGuard() { expect("PROPOSAL_AMOUNT_INVALID", () -> Proposal.activate(12_000, T0, true, false)); }
    private static void metaTrigger() {
        MetaNow m = MetaNow.create(150_000, 120_000, T0, 120); eq(80, m.progressPercent());
        m = m.updateHighest(150_000, T0.plusSeconds(60)); eq(MetaNowState.TRIGGERED, m.state()); eq(100, m.progressPercent());
    }
    private static void metaCancelGuard() {
        MetaNow m = MetaNow.create(150_000, 150_000, T0, 120);
        expect("META_ALREADY_REACHED", () -> m.cancel(T0.plusSeconds(1)));
    }
    private static void auctionBaseDuration() {
        Auction a = Auction.open(T0, 80_000, 300_000L); eq(T0.plusSeconds(900), a.deadline());
    }
    private static void antiSniping() {
        Auction a = Auction.open(T0, 80_000, null);
        Instant bidAt = a.deadline().minusSeconds(119);
        a = a.acceptBid(90_000, bidAt, true, true);
        eq(AuctionState.EXTENDED, a.state()); eq(bidAt.plusSeconds(120), a.deadline());
        Instant second = a.deadline().minusSeconds(1);
        a = a.acceptBid(100_000, second, true, true); eq(second.plusSeconds(120), a.deadline());
    }
    private static void fundingFailureNoExtension() {
        Auction a = Auction.open(T0, 80_000, null); Instant d=a.deadline();
        expect("BID_FUNDING_FAILED", () -> a.acceptBid(90_000, d.minusSeconds(5), false, true)); eq(d, a.deadline());
    }
    private static void closeNow() {
        Auction a=Auction.open(T0,80_000,300_000L).closeNow(T0.plusSeconds(30),300_000,true,true);
        eq(AuctionState.CLOSED_NOW,a.state()); eq(300_000L,a.currentAmountClp());
    }
    private static void winnerConfirm() {
        WinnerConfirmation w=WinnerConfirmation.select(T0,1).confirm(T0.plusSeconds(179)); eq(WinnerState.CONFIRMED,w.state()); isTrue(w.backupsClosed());
    }
    private static void winnerBackups() {
        WinnerConfirmation w=WinnerConfirmation.select(T0,1).timeout(T0.plusSeconds(180)); eq(10,w.penaltyPercent());
        w=w.nextBackup(T0.plusSeconds(181)); eq(2,w.candidateNo());
        w=w.timeout(T0.plusSeconds(361)).nextBackup(T0.plusSeconds(362)); eq(3,w.candidateNo());
        w=w.timeout(T0.plusSeconds(542)).nextBackup(T0.plusSeconds(543)); eq(4,w.candidateNo());
        WinnerConfirmation last=w.timeout(T0.plusSeconds(723)); expect("BACKUP_EXHAUSTED", () -> last.nextBackup(T0.plusSeconds(724)));
    }
    private static void etaGuard() { expect("ETA_INELIGIBLE", () -> Appointment.enRoute(T0,31)); eq(AppointmentState.EN_ROUTE,Appointment.enRoute(T0,30).state()); }
    private static void arrival() {
        Appointment a=Appointment.enRoute(T0,10).arrive(T0.plusSeconds(600),true); eq(AppointmentState.ARRIVED,a.state()); eq(T0.plusSeconds(900),a.handshakeDeadline());
    }
    private static void handshake() {
        Appointment a=Appointment.enRoute(T0,1).arrive(T0.plusSeconds(60),true);
        Appointment base=a; expect("HANDSHAKE_NOT_BILATERAL", () -> base.confirmMeeting(T0.plusSeconds(61),true,false));
        a=a.extendHandshake(T0.plusSeconds(100),true); eq(T0.plusSeconds(60+600),a.handshakeDeadline());
        Appointment extended=a; expect("HANDSHAKE_EXTENSION_USED", () -> extended.extendHandshake(T0.plusSeconds(120),true));
        a=a.confirmMeeting(T0.plusSeconds(200),true,true); eq(AppointmentState.MEETING_CONFIRMED,a.state());
    }
    private static void presentialFree() {
        PresentialSession s=PresentialSession.startFree(T0,true); isFalse(s.billable());
        PresentialSession free=s; expect("FREE_NOT_DUE", () -> free.freeTimeout(T0.plusSeconds(299)));
        s=s.freeTimeout(T0.plusSeconds(300)); eq(PresentialSessionState.PAID_ACTIVE,s.state()); isTrue(s.billable());
    }
    private static void presentialPauseResume() {
        PresentialSession s=PresentialSession.startFree(T0,true).freeTimeout(T0.plusSeconds(300)).pause(); isFalse(s.billable());
        PresentialSession paused=s; expect("RESUME_NOT_BILATERAL", () -> paused.resume(true,false,T0.plusSeconds(320)));
        s=s.resume(true,true,T0.plusSeconds(321)); isTrue(s.billable());
    }
    private static void onlineJoin() {
        OnlineSession s=OnlineSession.winnerSelected().openJoinWindow(T0);
        eq(T0.plusSeconds(180),s.joinDeadline());
        expect("ONLINE_JOIN_TIMEOUT", () -> s.bothConnected(T0.plusSeconds(181),true,true));
    }
    private static void onlinePaidConsent() {
        OnlineSession s=OnlineSession.winnerSelected().openJoinWindow(T0).bothConnected(T0.plusSeconds(10),true,true);
        eq(OnlineState.FREE_ONLINE,s.state()); eq(T0.plusSeconds(130),s.freeEndsAt());
        s=s.freeTimeout(T0.plusSeconds(130),true); eq(OnlineState.AWAITING_PAID_CONFIRMATION,s.state());
        s=s.acceptPaid(true,T0.plusSeconds(131),true); eq(OnlineState.AWAITING_PAID_CONFIRMATION,s.state());
        s=s.acceptPaid(false,T0.plusSeconds(132),true); eq(OnlineState.PAID_ACTIVE,s.state()); isTrue(s.billable());
    }
    private static void onlineConsentTimeout() {
        OnlineSession s=OnlineSession.winnerSelected().openJoinWindow(T0).bothConnected(T0.plusSeconds(10),true,true).freeTimeout(T0.plusSeconds(130),true);
        s=s.paidConsentTimeout(T0.plusSeconds(160)); eq(OnlineState.FINISHED_FREE,s.state()); isFalse(s.billable());
    }
    private static void onlineMicrocut() {
        OnlineSession s=paidOnline();
        OnlineSession x=s; expect("ONLINE_MICROCUT_TOLERANCE", () -> x.confirmMediaInterruption(T0.plusSeconds(136)));
        s=s.confirmMediaInterruption(T0.plusSeconds(138)); eq(OnlineState.RECONNECTING,s.state());
    }
    private static void onlineReconnect() {
        OnlineSession s=paidOnline().confirmMediaInterruption(T0.plusSeconds(138));
        OnlineSession r=s; expect("ONLINE_RECONNECT_GUARD", () -> r.resumeAfterReconnect(T0.plusSeconds(150),true,true,false));
        s=s.resumeAfterReconnect(T0.plusSeconds(150),true,true,true); eq(OnlineState.PAID_ACTIVE,s.state());
    }
    private static void onlineReconnectTimeout() {
        OnlineSession s=paidOnline().confirmMediaInterruption(T0.plusSeconds(138));
        s=s.reconnectTimeout(T0.plusSeconds(258)); eq(OnlineState.FINISHED_RECONNECT_TIMEOUT,s.state());
    }
    private static OnlineSession paidOnline() {
        OnlineSession s=OnlineSession.winnerSelected().openJoinWindow(T0).bothConnected(T0.plusSeconds(10),true,true).freeTimeout(T0.plusSeconds(130),true);
        s=s.acceptPaid(true,T0.plusSeconds(131),true).acceptPaid(false,T0.plusSeconds(132),true);
        return s.mediaHeartbeat(T0.plusSeconds(132));
    }
    private static void extensionRounds() {
        ExtensionNegotiation e=ExtensionNegotiation.none().propose(30,80_000,true,false);
        e=e.counter(30,65_000).counter(30,70_000); eq(3,e.round());
        ExtensionNegotiation x=e; expect("EXTENSION_ROUNDS_EXHAUSTED", () -> x.counter(30,75_000));
        e=e.accept(true,true); eq(ExtensionState.ACCEPTED,e.state());
    }
    private static void extensionFunds() {
        ExtensionNegotiation e=ExtensionNegotiation.none().propose(15,50_000,true,false);
        ExtensionNegotiation x=e; expect("EXTENSION_FUNDS_FAILED", () -> x.accept(true,false));
    }

    private static void bidderNoShow() {
        eq(10, NoShowPolicy.bidder(NoShowPolicy.BidderStage.NO_CONFIRM_3_MIN,0).penaltyPercent());
        eq(15, NoShowPolicy.bidder(NoShowPolicy.BidderStage.CONFIRMED_CANCEL_BEFORE_ARRIVAL,0).penaltyPercent());
        eq(20, NoShowPolicy.bidder(NoShowPolicy.BidderStage.CONFIRMED_NO_SHOW,0).penaltyPercent());
        eq(35, NoShowPolicy.bidder(NoShowPolicy.BidderStage.CONFIRMED_NO_SHOW,1).penaltyPercent());
        var third=NoShowPolicy.bidder(NoShowPolicy.BidderStage.CONFIRMED_NO_SHOW,2); eq(50,third.penaltyPercent()); isTrue(third.suspendBiddingAndReview());
    }
    private static void hostNoShow() {
        eq(24,NoShowPolicy.hostConfirmedNoShow(0).suspensionHours());
        eq(7*24,NoShowPolicy.hostConfirmedNoShow(1).suspensionHours());
        var third=NoShowPolicy.hostConfirmedNoShow(2); eq(30*24,third.suspensionHours()); isTrue(third.review()); eq(100,third.refundPercentToWinner()); eq(0,third.hostRevenuePercent());
    }
    private static void liveStartGuard() {
        expect("LIVE_TOO_LATE", () -> LiveSession.start(T0,T0.plusSeconds(299),true));
        eq(LiveState.LIVE,LiveSession.start(T0,T0.plusSeconds(300),true).state());
    }
    private static void ticketPricing() {
        eq(1500,LiveSession.ticketPriceClp(480)); eq(1000,LiveSession.ticketPriceClp(479)); eq(1000,LiveSession.ticketPriceClp(180));
        expect("TICKET_CUTOFF", () -> LiveSession.ticketPriceClp(179));
    }
    private static void liveReconnect() {
        LiveSession l=LiveSession.start(T0,T0.plusSeconds(600),true).connectionLost(T0.plusSeconds(30));
        l=l.reconnect(T0.plusSeconds(149)); eq(LiveState.LIVE,l.state());
    }
    private static void payoutHold() {
        Payout p=Payout.pending(T0); Payout x=p; expect("PAYOUT_HOLD_ACTIVE", () -> x.release(T0.plusSeconds(3599),false));
        p=p.release(T0.plusSeconds(3600),false); eq(PayoutState.AVAILABLE,p.state());
    }
    private static void payoutIncident() { eq(PayoutState.HELD_FOR_REVIEW,Payout.pending(T0).holdForReview(true).state()); }
    private static void s5HumanReview() {
        SafetyCase c=SafetyCase.reported().triage(true); SafetyCase x=c;
        expect("SAFETY_S5_HUMAN_REQUIRED", () -> x.confirm(SafetyLevel.S5,T0,false));
        c=c.confirm(SafetyLevel.S5,T0,true); eq(SafetyLevel.S5,c.level());
    }
    private static void safetyUndetermined() { SafetyCase c=SafetyCase.reported().triage(true).undetermined(); eq(SafetyCaseState.UNDETERMINED,c.state()); eq(SafetyLevel.S0,c.level()); }
    private static void appealWindow() {
        SafetyCase c=SafetyCase.reported().triage(true).confirm(SafetyLevel.S3,T0,false);
        Appeal a=Appeal.open(c,T0.plusSeconds(7L*24*3600)); eq(AppealOutcome.OPEN,a.outcome());
        expect("APPEAL_LATE", () -> Appeal.open(c,T0.plusSeconds(7L*24*3600+1)));
    }
    private static void appealOutcomes() {
        SafetyCase c=SafetyCase.reported().triage(true).confirm(SafetyLevel.S3,T0,false);
        Appeal a=Appeal.open(c,T0.plusSeconds(1)).reduce(SafetyLevel.S2,T0.plusSeconds(2)); eq(AppealOutcome.REDUCE,a.outcome()); eq(SafetyLevel.S2,a.finalLevel());
        Appeal b=Appeal.open(c,T0.plusSeconds(3)).revoke(T0.plusSeconds(4)); eq(SafetyLevel.S0,b.finalLevel());
    }

    private static void test(String name, Runnable r) { try { r.run(); passed++; System.out.println("[PASS] " + name); } catch(Throwable t) { System.err.println("[FAIL] " + name + ": " + t); t.printStackTrace(); System.exit(1); } }
    private static void expect(String code, Runnable r) { try { r.run(); throw new AssertionError("Expected TransitionException " + code); } catch(TransitionException e) { if(!code.equals(e.code())) throw new AssertionError("Expected "+code+" got "+e.code()); } }
    private static void eq(Object e,Object a){ if(!java.util.Objects.equals(e,a)) throw new AssertionError("Expected "+e+" got "+a); }
    private static void eq(long e,long a){ if(e!=a) throw new AssertionError("Expected "+e+" got "+a); }
    private static void isTrue(boolean x){ if(!x) throw new AssertionError("Expected true"); }
    private static void isFalse(boolean x){ if(x) throw new AssertionError("Expected false"); }
}

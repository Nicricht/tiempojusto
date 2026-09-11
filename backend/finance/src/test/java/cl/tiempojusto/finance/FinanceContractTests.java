package cl.tiempojusto.finance;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.ledger.InMemoryLedger;
import cl.tiempojusto.finance.ledger.InMemoryLedger.*;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort.*;
import cl.tiempojusto.finance.settlement.FinanceEngine;
import cl.tiempojusto.finance.settlement.FinanceEngine.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FinanceContractTests {
    private static final Instant T0 = Instant.parse("2026-08-14T12:00:00Z");
    private static int passed;

    public static void main(String[] args) {
        test("reserve backed funds", FinanceContractTests::reserveBackedFunds);
        test("reserve idempotency", FinanceContractTests::reserveIdempotency);
        test("idempotency payload conflict", FinanceContractTests::idempotencyConflict);
        test("adjust reservation up and down", FinanceContractTests::adjustReservation);
        test("release reservation", FinanceContractTests::releaseReservation);
        test("partial capture", FinanceContractTests::partialCapture);
        test("partial refund", FinanceContractTests::partialRefund);
        test("provider deterministic failure", FinanceContractTests::providerFailure);
        test("session settlement 80/20 and unused release", FinanceContractTests::sessionSettlement);
        test("session settlement idempotency", FinanceContractTests::sessionSettlementIdempotency);
        test("extension uses 80/20", FinanceContractTests::extensionSettlement);
        test("live ticket settlement 70/30", FinanceContractTests::liveSettlement);
        test("hold is exactly 60 minutes", FinanceContractTests::hold60);
        test("objective incident blocks automatic release", FinanceContractTests::incidentHold);
        test("payout only from AVAILABLE", FinanceContractTests::payoutAvailableOnly);
        test("payout idempotency", FinanceContractTests::payoutIdempotency);
        test("host no-show returns 100 percent pre-capture", FinanceContractTests::hostNoShow);
        test("compliant host chargeback creates no automatic debt", FinanceContractTests::chargebackNoAutomaticDebt);
        test("confirmed fraud recovery requires evidence", FinanceContractTests::fraudRecoveryEvidence);
        test("confirmed fraud recovery is ledger based", FinanceContractTests::fraudRecoveryLedger);
        test("ledger rejects unbalanced transaction", FinanceContractTests::ledgerRejectsUnbalanced);
        test("ledger compensation never edits history", FinanceContractTests::ledgerCompensation);
        System.out.println("\nPASS: " + passed + " finance contract tests");
    }

    private static void reserveBackedFunds() {
        Fixture f = fixture(100_000);
        Reservation r = f.engine.reserveBid(f.bidder, 80_000, "r1", T0);
        eq(80_000L, r.remainingReservedClp()); eq(20_000L, f.port.availableFunds(f.bidder));
        expect("PAYMENT_INSUFFICIENT_FUNDS", () -> f.engine.reserveBid(f.bidder, 30_000, "r2", T0));
    }

    private static void reserveIdempotency() {
        Fixture f = fixture(100_000);
        Reservation a = f.engine.reserveBid(f.bidder, 80_000, "r1", T0);
        Reservation b = f.engine.reserveBid(f.bidder, 80_000, "r1", T0.plusSeconds(5));
        eq(a.id(), b.id()); eq(20_000L, f.port.availableFunds(f.bidder));
    }

    private static void idempotencyConflict() {
        Fixture f = fixture(100_000);
        f.engine.reserveBid(f.bidder, 50_000, "same", T0);
        expect("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", () -> f.engine.reserveBid(f.bidder, 60_000, "same", T0));
    }

    private static void adjustReservation() {
        Fixture f = fixture(150_000);
        Reservation r = f.engine.reserveBid(f.bidder, 50_000, "r", T0);
        r = f.engine.adjustBidReservation(r.id(), 90_000, "up", T0); eq(60_000L, f.port.availableFunds(f.bidder));
        r = f.engine.adjustBidReservation(r.id(), 70_000, "down", T0); eq(80_000L, f.port.availableFunds(f.bidder));
    }

    private static void releaseReservation() {
        Fixture f = fixture(100_000);
        Reservation r = f.engine.reserveBid(f.bidder, 80_000, "r", T0);
        r = f.engine.releaseBidReservation(r.id(), "release", T0);
        eq(ReservationStatus.RELEASED, r.status()); eq(100_000L, f.port.availableFunds(f.bidder));
    }

    private static void partialCapture() {
        Fixture f = fixture(100_000);
        Reservation r = f.engine.reserveBid(f.bidder, 80_000, "r", T0);
        Capture c = f.port.capture(r.id(), 30_000, "c", T0);
        eq(30_000L, c.amountClp()); eq(50_000L, f.port.reservation(r.id()).remainingReservedClp());
        eq(30_000L, f.port.providerClearingClp());
    }

    private static void partialRefund() {
        Fixture f = fixture(100_000);
        Reservation r = f.engine.reserveBid(f.bidder, 80_000, "r", T0);
        Capture c = f.port.capture(r.id(), 50_000, "c", T0);
        Refund refund = f.port.refund(c.id(), 20_000, "rf", T0);
        eq(20_000L, refund.amountClp()); eq(40_000L, f.port.availableFunds(f.bidder)); eq(30_000L, f.port.providerClearingClp());
    }

    private static void providerFailure() {
        Fixture f = fixture(100_000);
        f.port.failNext(Operation.RESERVE, FailureCode.TIMEOUT);
        expect("PAYMENT_TIMEOUT", () -> f.engine.reserveBid(f.bidder, 50_000, "retry-key", T0));
        Reservation r = f.engine.reserveBid(f.bidder, 50_000, "retry-key", T0.plusSeconds(1));
        eq(50_000L, r.remainingReservedClp());
    }

    private static void sessionSettlement() {
        Fixture f = fixture(200_000);
        Reservation r = f.engine.reserveBid(f.bidder, 180_000, "r", T0);
        SettlementResult s = f.engine.settleSession(f.host, r.id(), 90_000, UUID.randomUUID(), "settle", T0);
        eq(72_000L, s.earning().hostAmountClp()); eq(18_000L, s.earning().platformAmountClp());
        eq(EarningState.PENDING, s.earning().state()); eq(110_000L, f.port.availableFunds(f.bidder));
        eq(0L, s.reservationAfterRelease().remainingReservedClp());
        eq(90_000L, balance(f, OwnerType.PROVIDER_CLEARING, null, AccountType.ESCROW));
        eq(72_000L, balance(f, OwnerType.USER, f.host, AccountType.PENDING));
        eq(18_000L, balance(f, OwnerType.PLATFORM, null, AccountType.REVENUE));
    }

    private static void sessionSettlementIdempotency() {
        Fixture f = fixture(200_000);
        Reservation r = f.engine.reserveBid(f.bidder, 180_000, "r", T0);
        UUID sessionId = UUID.randomUUID();
        SettlementResult a = f.engine.settleSession(f.host, r.id(), 90_000, sessionId, "settle", T0);
        SettlementResult b = f.engine.settleSession(f.host, r.id(), 90_000, sessionId, "settle", T0.plusSeconds(2));
        eq(a.earning().id(), b.earning().id()); eq(1, f.ledger.transactionCount());
    }

    private static void extensionSettlement() {
        Fixture f = fixture(100_000);
        Reservation r = f.engine.reserveBid(f.bidder, 50_000, "r", T0);
        SettlementResult s = f.engine.settleExtension(f.host, r.id(), 25_000, UUID.randomUUID(), "ext", T0);
        eq(20_000L, s.earning().hostAmountClp()); eq(5_000L, s.earning().platformAmountClp());
    }

    private static void liveSettlement() {
        Fixture f = fixture(10_000);
        SettlementResult s = f.engine.settleLiveTicket(f.host, f.bidder, 1_500, UUID.randomUUID(), "live", T0);
        eq(1_050L, s.earning().hostAmountClp()); eq(450L, s.earning().platformAmountClp());
        eq(SettlementKind.LIVE_TICKET, s.earning().kind());
    }

    private static void hold60() {
        Fixture f = settledSession(100_000, 100_000);
        Earning e = f.last;
        expect("PAYOUT_HOLD_NOT_EXPIRED", () -> f.engine.releaseHold(e.id(), T0.plusSeconds(3599), "h1"));
        Earning available = f.engine.releaseHold(e.id(), T0.plusSeconds(3600), "h2");
        eq(EarningState.AVAILABLE, available.state()); eq(80_000L, balance(f, OwnerType.USER, f.host, AccountType.AVAILABLE));
        eq(0L, balance(f, OwnerType.USER, f.host, AccountType.PENDING));
    }

    private static void incidentHold() {
        Fixture f = settledSession(100_000, 100_000);
        f.engine.holdForReview(f.last.id());
        expect("PAYOUT_HELD_FOR_REVIEW", () -> f.engine.releaseHold(f.last.id(), T0.plusSeconds(7200), "release"));
    }

    private static void payoutAvailableOnly() {
        Fixture f = settledSession(100_000, 100_000);
        expect("PAYOUT_NOT_AVAILABLE", () -> f.engine.requestPayout(f.last.id(), "p1", T0.plusSeconds(10)));
        f.engine.releaseHold(f.last.id(), T0.plusSeconds(3600), "hold");
        PayoutTransfer p = f.engine.requestPayout(f.last.id(), "p2", T0.plusSeconds(3601));
        eq(80_000L, p.amountClp()); eq(EarningState.PAID_OUT, f.engine.earning(f.last.id()).state());
        eq(0L, balance(f, OwnerType.USER, f.host, AccountType.AVAILABLE));
        eq(20_000L, f.port.providerClearingClp());
    }

    private static void payoutIdempotency() {
        Fixture f = settledSession(100_000, 100_000);
        f.engine.releaseHold(f.last.id(), T0.plusSeconds(3600), "hold");
        PayoutTransfer a = f.engine.requestPayout(f.last.id(), "pay", T0.plusSeconds(3601));
        PayoutTransfer b = f.engine.requestPayout(f.last.id(), "pay", T0.plusSeconds(3602));
        eq(a.id(), b.id());
    }

    private static void hostNoShow() {
        Fixture f = fixture(100_000);
        Reservation r = f.engine.reserveBid(f.bidder, 100_000, "r", T0);
        Reservation released = f.engine.hostNoShowRefund100(r.id(), "noshow", T0.plusSeconds(60));
        eq(ReservationStatus.RELEASED, released.status()); eq(100_000L, f.port.availableFunds(f.bidder));
        eq(0, f.ledger.transactionCount());
    }

    private static void chargebackNoAutomaticDebt() {
        Fixture f = settledSession(100_000, 100_000);
        long before = balance(f, OwnerType.USER, f.host, AccountType.PENDING);
        ChargebackCase c = f.engine.openChargeback(f.host, f.last.captureId(), 100_000, "cb-1", true, "cb", T0.plusSeconds(10));
        isFalse(c.automaticHostDebtCreated());
        eq(before, balance(f, OwnerType.USER, f.host, AccountType.PENDING));
    }

    private static void fraudRecoveryEvidence() {
        Fixture f = fixture(0);
        expect("FRAUD_EVIDENCE_REQUIRED", () -> f.engine.recordConfirmedFraudRecovery(f.host, 10_000, "", UUID.randomUUID(), "fraud", T0));
    }

    private static void fraudRecoveryLedger() {
        Fixture f = fixture(0);
        f.engine.recordConfirmedFraudRecovery(f.host, 10_000, "case-evidence-42", UUID.randomUUID(), "fraud", T0);
        eq(-10_000L, balance(f, OwnerType.USER, f.host, AccountType.AVAILABLE));
        eq(10_000L, balance(f, OwnerType.PLATFORM, null, AccountType.DISPUTE));
    }

    private static void ledgerRejectsUnbalanced() {
        InMemoryLedger l = new InMemoryLedger();
        Account a = l.account(OwnerType.PLATFORM, null, AccountType.REVENUE);
        Account b = l.account(OwnerType.PROVIDER_CLEARING, null, AccountType.ESCROW);
        expect("LEDGER_UNBALANCED", () -> l.post(TransactionType.CAPTURE, "T", UUID.randomUUID(), "bad", T0,
                List.of(new Posting(a, Direction.CREDIT, 10_000), new Posting(b, Direction.DEBIT, 9_000))));
    }

    private static void ledgerCompensation() {
        InMemoryLedger l = new InMemoryLedger();
        Account clearing = l.account(OwnerType.PROVIDER_CLEARING, null, AccountType.ESCROW);
        Account revenue = l.account(OwnerType.PLATFORM, null, AccountType.REVENUE);
        Transaction original = l.post(TransactionType.CAPTURE, "X", UUID.randomUUID(), "orig", T0,
                List.of(new Posting(clearing, Direction.DEBIT, 10_000), new Posting(revenue, Direction.CREDIT, 10_000)));
        List<Entry> snapshot = original.entries();
        l.compensate(original.id(), "CORRECTION", UUID.randomUUID(), "comp", T0.plusSeconds(1));
        eq(snapshot, l.transaction(original.id()).entries()); eq(2, l.transactionCount()); eq(0L, l.balanceClp(clearing)); eq(0L, l.balanceClp(revenue));
    }

    private static Fixture settledSession(long reserved, long generated) {
        Fixture f = fixture(reserved);
        Reservation r = f.engine.reserveBid(f.bidder, reserved, "reserve", T0);
        SettlementResult result = f.engine.settleSession(f.host, r.id(), generated, UUID.randomUUID(), "settle", T0);
        f.last = result.earning();
        return f;
    }

    private static Fixture fixture(long bidderFunds) {
        UUID bidder = UUID.randomUUID(); UUID host = UUID.randomUUID();
        MockPaymentPort port = new MockPaymentPort(); port.setAvailableFunds(bidder, bidderFunds);
        InMemoryLedger ledger = new InMemoryLedger(); FinanceEngine engine = new FinanceEngine(port, ledger);
        return new Fixture(bidder, host, port, ledger, engine);
    }

    private static long balance(Fixture f, OwnerType ownerType, UUID ownerId, AccountType accountType) {
        return f.ledger.balanceClp(f.ledger.account(ownerType, ownerId, accountType));
    }

    private static void test(String name, Runnable r) {
        try { r.run(); passed++; System.out.println("PASS  " + name); }
        catch (Throwable t) { System.err.println("FAIL  " + name + " -> " + t); throw t; }
    }

    private static void expect(String code, Runnable r) {
        try { r.run(); throw new AssertionError("Expected " + code); }
        catch (FinanceException e) { if (!code.equals(e.code())) throw new AssertionError("Expected " + code + " but got " + e.code(), e); }
    }

    private static void eq(Object expected, Object actual) { if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but got " + actual); }
    private static void eq(long expected, long actual) { if (expected != actual) throw new AssertionError("Expected " + expected + " but got " + actual); }
    private static void eq(int expected, int actual) { if (expected != actual) throw new AssertionError("Expected " + expected + " but got " + actual); }
    private static void isFalse(boolean value) { if (value) throw new AssertionError("Expected false"); }

    private static final class Fixture {
        final UUID bidder; final UUID host; final MockPaymentPort port; final InMemoryLedger ledger; final FinanceEngine engine;
        Earning last;
        Fixture(UUID bidder, UUID host, MockPaymentPort port, InMemoryLedger ledger, FinanceEngine engine) {
            this.bidder = bidder; this.host = host; this.port = port; this.ledger = ledger; this.engine = engine;
        }
    }
}

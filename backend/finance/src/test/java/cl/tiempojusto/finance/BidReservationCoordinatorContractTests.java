package cl.tiempojusto.finance;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.BidReservationCoordinator;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BidReservationCoordinatorContractTests {
    private static int passed;
    private static final Instant T0 = Instant.parse("2026-09-12T00:00:00Z");

    public static void main(String[] args) {
        run("new reservation covers first bid", BidReservationCoordinatorContractTests::newReservation);
        run("supported adjust uses only delta funding", BidReservationCoordinatorContractTests::adjustInPlace);
        run("unsupported adjust reserves replacement before old release", BidReservationCoordinatorContractTests::replacementFirst);
        run("replacement release is explicit and idempotent", BidReservationCoordinatorContractTests::releaseSuperseded);
        run("non-capability failures do not fallback", BidReservationCoordinatorContractTests::noFallbackOnFundingFailure);
        System.out.println("PASS: " + passed + "/5 BidReservationCoordinator contract tests");
    }

    private static void newReservation() {
        UUID payer = UUID.randomUUID();
        MockPaymentPort port = new MockPaymentPort();
        port.setAvailableFunds(payer, 100_000);
        BidReservationCoordinator c = new BidReservationCoordinator(port);
        var coverage = c.cover(payer, null, 50_000, "bid-1", T0);
        eq(BidReservationCoordinator.Strategy.NEW, coverage.strategy());
        eq(50_000L, coverage.reservation().remainingReservedClp());
        eq(50_000L, port.availableFunds(payer));
        ok(!coverage.releaseSupersededAfterCommit());
    }

    private static void adjustInPlace() {
        UUID payer = UUID.randomUUID();
        MockPaymentPort port = new MockPaymentPort();
        port.setAvailableFunds(payer, 100_000);
        BidReservationCoordinator c = new BidReservationCoordinator(port);
        var first = c.cover(payer, null, 50_000, "bid-1", T0);
        var next = c.cover(payer, first.reservation().id(), 60_000, "bid-2", T0.plusSeconds(1));
        eq(BidReservationCoordinator.Strategy.ADJUSTED, next.strategy());
        eq(first.reservation().id(), next.reservation().id());
        eq(60_000L, next.reservation().remainingReservedClp());
        eq(40_000L, port.availableFunds(payer));
    }

    private static void replacementFirst() {
        UUID payer = UUID.randomUUID();
        UnsupportedAdjustPaymentPort port = new UnsupportedAdjustPaymentPort();
        port.setAvailable(payer, 200_000);
        BidReservationCoordinator c = new BidReservationCoordinator(port);
        var first = c.cover(payer, null, 50_000, "bid-1", T0);
        var next = c.cover(payer, first.reservation().id(), 60_000, "bid-2", T0.plusSeconds(1));
        eq(BidReservationCoordinator.Strategy.REPLACEMENT, next.strategy());
        eq(first.reservation().id(), next.supersededReservationId());
        ok(next.releaseSupersededAfterCommit());
        ok(port.reservation(first.reservation().id()).status() == PaymentPort.ReservationStatus.RESERVED);
        ok(port.reservation(next.reservation().id()).status() == PaymentPort.ReservationStatus.RESERVED);
    }

    private static void releaseSuperseded() {
        UUID payer = UUID.randomUUID();
        UnsupportedAdjustPaymentPort port = new UnsupportedAdjustPaymentPort();
        port.setAvailable(payer, 200_000);
        BidReservationCoordinator c = new BidReservationCoordinator(port);
        var first = c.cover(payer, null, 50_000, "bid-1", T0);
        var next = c.cover(payer, first.reservation().id(), 60_000, "bid-2", T0.plusSeconds(1));
        var released = c.releaseSuperseded(next.supersededReservationId(), "bid-2", T0.plusSeconds(2));
        eq(PaymentPort.ReservationStatus.RELEASED, released.status());
        var replay = c.releaseSuperseded(next.supersededReservationId(), "bid-2", T0.plusSeconds(3));
        eq(PaymentPort.ReservationStatus.RELEASED, replay.status());
    }

    private static void noFallbackOnFundingFailure() {
        UUID payer = UUID.randomUUID();
        MockPaymentPort port = new MockPaymentPort();
        port.setAvailableFunds(payer, 50_000);
        BidReservationCoordinator c = new BidReservationCoordinator(port);
        var first = c.cover(payer, null, 50_000, "bid-1", T0);
        expectCode("PAYMENT_INSUFFICIENT_FUNDS", () -> c.cover(
                payer, first.reservation().id(), 60_000, "bid-2", T0.plusSeconds(1)));
    }

    private static final class UnsupportedAdjustPaymentPort implements PaymentPort {
        private final Map<UUID, Reservation> reservations = new HashMap<>();
        private final Map<UUID, Long> available = new HashMap<>();
        private final Map<String, Reservation> idempotentReserve = new HashMap<>();
        private final Map<String, Reservation> idempotentRelease = new HashMap<>();

        void setAvailable(UUID payer, long amount) { available.put(payer, amount); }
        Reservation reservation(UUID id) { return reservations.get(id); }

        @Override public Reservation reserve(UUID payerUserId, long amountClp, String key, Instant now) {
            Reservation prior = idempotentReserve.get(key);
            if (prior != null) return prior;
            long funds = available.getOrDefault(payerUserId, 0L);
            if (funds < amountClp) throw new FinanceException("PAYMENT_INSUFFICIENT_FUNDS", "insufficient");
            available.put(payerUserId, funds - amountClp);
            Reservation r = new Reservation(UUID.randomUUID(), payerUserId, amountClp, amountClp,
                    ReservationStatus.RESERVED, now);
            reservations.put(r.id(), r);
            idempotentReserve.put(key, r);
            return r;
        }

        @Override public Reservation adjustReservation(UUID reservationId, long newAmountClp, String key, Instant now) {
            throw new FinanceException("PAYMENT_PROVIDER_CAPABILITY_UNSUPPORTED", "adjust unsupported");
        }

        @Override public Reservation releaseReservation(UUID reservationId, String key, Instant now) {
            Reservation prior = idempotentRelease.get(key);
            if (prior != null) return prior;
            Reservation current = reservations.get(reservationId);
            if (current.status() == ReservationStatus.RELEASED) return current;
            available.merge(current.payerUserId(), current.remainingReservedClp(), Long::sum);
            Reservation released = new Reservation(current.id(), current.payerUserId(), current.authorizedAmountClp(),
                    0, ReservationStatus.RELEASED, current.createdAt());
            reservations.put(released.id(), released);
            idempotentRelease.put(key, released);
            return released;
        }

        @Override public Capture capture(UUID reservationId, long amountClp, String key, Instant now) { throw unsupported(); }
        @Override public Refund refund(UUID captureId, long amountClp, String key, Instant now) { throw unsupported(); }
        @Override public PayoutTransfer payout(UUID hostUserId, long amountClp, String key, Instant now) { throw unsupported(); }
        @Override public PaymentDispute openDispute(UUID captureId, long amountClp, String providerReference, String key, Instant now) { throw unsupported(); }
        private FinanceException unsupported() { return new FinanceException("UNSUPPORTED", "not used"); }
    }

    private static void run(String name, Runnable r) {
        try {
            r.run();
            passed++;
            System.out.println("PASS " + passed + " - " + name);
        } catch (Throwable t) {
            throw new AssertionError("FAILED: " + name, t);
        }
    }

    private static void expectCode(String code, Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected " + code);
        } catch (FinanceException ex) {
            eq(code, ex.code());
        }
    }

    private static void ok(boolean condition) {
        if (!condition) throw new AssertionError("condition is false");
    }

    private static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}

package cl.tiempojusto.finance.payment;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.PaymentPort.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MockPaymentPort implements PaymentPort {
    private final Map<UUID, Long> availableFunds = new HashMap<>();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Map<UUID, Capture> captures = new HashMap<>();
    private final Map<UUID, Refund> refunds = new HashMap<>();
    private final Map<UUID, PayoutTransfer> payouts = new HashMap<>();
    private final Map<UUID, PaymentDispute> disputes = new HashMap<>();
    private final Map<String, IdempotentResult> idempotency = new HashMap<>();
    private final EnumMap<Operation, ArrayDeque<FailureCode>> failures = new EnumMap<>(Operation.class);
    private long providerClearingClp;
    private long payoutSequence;

    public MockPaymentPort() {
        for (Operation operation : Operation.values()) {
            failures.put(operation, new ArrayDeque<>());
        }
    }

    public synchronized void setAvailableFunds(UUID payerUserId, long amountClp) {
        requireNonNegative(amountClp, "amountClp");
        availableFunds.put(Objects.requireNonNull(payerUserId), amountClp);
    }

    public synchronized long availableFunds(UUID payerUserId) {
        return availableFunds.getOrDefault(payerUserId, 0L);
    }

    public synchronized long providerClearingClp() {
        return providerClearingClp;
    }

    public synchronized Reservation reservation(UUID id) {
        return requiredReservation(id);
    }

    public synchronized Capture capture(UUID id) {
        Capture capture = captures.get(id);
        if (capture == null) throw error("PAYMENT_CAPTURE_NOT_FOUND", "Capture does not exist");
        return capture;
    }

    public synchronized void failNext(Operation operation, FailureCode code) {
        failures.get(Objects.requireNonNull(operation)).addLast(Objects.requireNonNull(code));
    }

    @Override
    public synchronized Reservation reserve(UUID payerUserId, long amountClp, String key, Instant now) {
        requirePositive(amountClp, "amountClp");
        String fp = "reserve|" + payerUserId + "|" + amountClp;
        Object prior = prior(Operation.RESERVE, key, fp);
        if (prior != null) return (Reservation) prior;
        maybeFail(Operation.RESERVE);

        long available = availableFunds(payerUserId);
        if (available < amountClp) throw error("PAYMENT_INSUFFICIENT_FUNDS", "Insufficient funds to reserve");
        availableFunds.put(payerUserId, available - amountClp);
        Reservation r = new Reservation(UUID.randomUUID(), payerUserId, amountClp, amountClp, ReservationStatus.RESERVED, now);
        reservations.put(r.id(), r);
        remember(Operation.RESERVE, key, fp, r);
        return r;
    }

    @Override
    public synchronized Reservation adjustReservation(UUID reservationId, long newAmountClp, String key, Instant now) {
        requirePositive(newAmountClp, "newAmountClp");
        String fp = "adjust|" + reservationId + "|" + newAmountClp;
        Object prior = prior(Operation.ADJUST_RESERVATION, key, fp);
        if (prior != null) return (Reservation) prior;
        maybeFail(Operation.ADJUST_RESERVATION);

        Reservation current = requiredReservation(reservationId);
        if (current.status() == ReservationStatus.RELEASED || current.status() == ReservationStatus.CAPTURED) {
            throw error("PAYMENT_RESERVATION_FINALIZED", "Reservation can no longer be adjusted");
        }
        long alreadyCaptured = current.authorizedAmountClp() - current.remainingReservedClp();
        if (newAmountClp < alreadyCaptured) {
            throw error("PAYMENT_ADJUST_BELOW_CAPTURED", "New reservation amount is below already captured amount");
        }
        long newRemaining = newAmountClp - alreadyCaptured;
        long delta = newRemaining - current.remainingReservedClp();
        long available = availableFunds(current.payerUserId());
        if (delta > 0 && available < delta) throw error("PAYMENT_INSUFFICIENT_FUNDS", "Insufficient funds to increase reservation");
        availableFunds.put(current.payerUserId(), available - delta);
        ReservationStatus status = alreadyCaptured == 0 ? ReservationStatus.RESERVED : ReservationStatus.PARTIALLY_CAPTURED;
        Reservation updated = new Reservation(current.id(), current.payerUserId(), newAmountClp, newRemaining, status, current.createdAt());
        reservations.put(updated.id(), updated);
        remember(Operation.ADJUST_RESERVATION, key, fp, updated);
        return updated;
    }

    @Override
    public synchronized Reservation releaseReservation(UUID reservationId, String key, Instant now) {
        String fp = "release|" + reservationId;
        Object prior = prior(Operation.RELEASE_RESERVATION, key, fp);
        if (prior != null) return (Reservation) prior;
        maybeFail(Operation.RELEASE_RESERVATION);

        Reservation current = requiredReservation(reservationId);
        if (current.status() == ReservationStatus.RELEASED || current.remainingReservedClp() == 0) {
            remember(Operation.RELEASE_RESERVATION, key, fp, current);
            return current;
        }
        availableFunds.merge(current.payerUserId(), current.remainingReservedClp(), Long::sum);
        Reservation released = new Reservation(current.id(), current.payerUserId(), current.authorizedAmountClp(), 0,
                ReservationStatus.RELEASED, current.createdAt());
        reservations.put(released.id(), released);
        remember(Operation.RELEASE_RESERVATION, key, fp, released);
        return released;
    }

    @Override
    public synchronized Capture capture(UUID reservationId, long amountClp, String key, Instant now) {
        requirePositive(amountClp, "amountClp");
        String fp = "capture|" + reservationId + "|" + amountClp;
        Object prior = prior(Operation.CAPTURE, key, fp);
        if (prior != null) return (Capture) prior;
        maybeFail(Operation.CAPTURE);

        Reservation current = requiredReservation(reservationId);
        if (current.status() == ReservationStatus.RELEASED) throw error("PAYMENT_RESERVATION_RELEASED", "Reservation is released");
        if (current.remainingReservedClp() < amountClp) throw error("PAYMENT_CAPTURE_EXCEEDS_RESERVATION", "Capture exceeds reserved funds");

        long remaining = current.remainingReservedClp() - amountClp;
        ReservationStatus state = remaining == 0 ? ReservationStatus.CAPTURED : ReservationStatus.PARTIALLY_CAPTURED;
        reservations.put(current.id(), new Reservation(current.id(), current.payerUserId(), current.authorizedAmountClp(), remaining, state, current.createdAt()));
        providerClearingClp += amountClp;
        Capture capture = new Capture(UUID.randomUUID(), reservationId, current.payerUserId(), amountClp, amountClp, now);
        captures.put(capture.id(), capture);
        remember(Operation.CAPTURE, key, fp, capture);
        return capture;
    }

    @Override
    public synchronized Refund refund(UUID captureId, long amountClp, String key, Instant now) {
        requirePositive(amountClp, "amountClp");
        String fp = "refund|" + captureId + "|" + amountClp;
        Object prior = prior(Operation.REFUND, key, fp);
        if (prior != null) return (Refund) prior;
        maybeFail(Operation.REFUND);

        Capture capture = requiredCapture(captureId);
        if (capture.refundableRemainingClp() < amountClp) throw error("PAYMENT_REFUND_EXCEEDS_CAPTURE", "Refund exceeds refundable amount");
        if (providerClearingClp < amountClp) throw error("PAYMENT_CLEARING_INSUFFICIENT", "Provider clearing is insufficient for refund");
        providerClearingClp -= amountClp;
        availableFunds.merge(capture.payerUserId(), amountClp, Long::sum);
        captures.put(capture.id(), new Capture(capture.id(), capture.reservationId(), capture.payerUserId(), capture.amountClp(), capture.refundableRemainingClp() - amountClp, capture.createdAt()));
        Refund refund = new Refund(UUID.randomUUID(), captureId, capture.payerUserId(), amountClp, now);
        refunds.put(refund.id(), refund);
        remember(Operation.REFUND, key, fp, refund);
        return refund;
    }

    @Override
    public synchronized PayoutTransfer payout(UUID hostUserId, long amountClp, String key, Instant now) {
        requirePositive(amountClp, "amountClp");
        String fp = "payout|" + hostUserId + "|" + amountClp;
        Object prior = prior(Operation.PAYOUT, key, fp);
        if (prior != null) return (PayoutTransfer) prior;
        maybeFail(Operation.PAYOUT);
        if (providerClearingClp < amountClp) throw error("PAYMENT_CLEARING_INSUFFICIENT", "Provider clearing is insufficient for payout");
        providerClearingClp -= amountClp;
        PayoutTransfer payout = new PayoutTransfer(UUID.randomUUID(), hostUserId, amountClp,
                "mock-payout-" + (++payoutSequence), now);
        payouts.put(payout.id(), payout);
        remember(Operation.PAYOUT, key, fp, payout);
        return payout;
    }

    @Override
    public synchronized PaymentDispute openDispute(UUID captureId, long amountClp, String providerReference, String key, Instant now) {
        requirePositive(amountClp, "amountClp");
        String fp = "dispute|" + captureId + "|" + amountClp + "|" + providerReference;
        Object prior = prior(Operation.OPEN_DISPUTE, key, fp);
        if (prior != null) return (PaymentDispute) prior;
        maybeFail(Operation.OPEN_DISPUTE);
        Capture capture = requiredCapture(captureId);
        if (amountClp > capture.amountClp()) throw error("PAYMENT_DISPUTE_EXCEEDS_CAPTURE", "Dispute exceeds capture amount");
        PaymentDispute dispute = new PaymentDispute(UUID.randomUUID(), captureId, amountClp,
                Objects.requireNonNull(providerReference), now);
        disputes.put(dispute.id(), dispute);
        remember(Operation.OPEN_DISPUTE, key, fp, dispute);
        return dispute;
    }

    private void maybeFail(Operation operation) {
        FailureCode code = failures.get(operation).pollFirst();
        if (code != null) throw error("PAYMENT_" + code.name(), "Mock provider failure: " + code);
    }

    private Object prior(Operation op, String key, String fingerprint) {
        requireKey(key);
        IdempotentResult prior = idempotency.get(op.name() + ":" + key);
        if (prior == null) return null;
        if (!prior.fingerprint.equals(fingerprint)) {
            throw error("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", "Idempotency key was reused with different payload");
        }
        return prior.value;
    }

    private void remember(Operation op, String key, String fingerprint, Object value) {
        idempotency.put(op.name() + ":" + key, new IdempotentResult(fingerprint, value));
    }

    private Reservation requiredReservation(UUID id) {
        Reservation r = reservations.get(id);
        if (r == null) throw error("PAYMENT_RESERVATION_NOT_FOUND", "Reservation does not exist");
        return r;
    }

    private Capture requiredCapture(UUID id) {
        Capture c = captures.get(id);
        if (c == null) throw error("PAYMENT_CAPTURE_NOT_FOUND", "Capture does not exist");
        return c;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency key is required");
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw error("PAYMENT_AMOUNT_INVALID", name + " must be > 0");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) throw error("PAYMENT_AMOUNT_INVALID", name + " must be >= 0");
    }

    private static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    private record IdempotentResult(String fingerprint, Object value) {}
}

package cl.tiempojusto.finance.payment;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.PaymentPort.Reservation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Coordinates financial coverage when a bidder increases their own Bid.
 *
 * Preferred path: adjust the existing provider reservation in place.
 * Fallback path: if the provider explicitly does not support reservation
 * adjustment, reserve the full new amount first and release the superseded
 * reservation only after the application transaction that accepts the new Bid
 * has committed.
 *
 * The coordinator deliberately never releases the old reservation inside
 * cover(). This prevents an accepted prior Bid from becoming uncovered if the
 * database transaction for the new Bid later rolls back.
 */
public final class BidReservationCoordinator {
    public enum Strategy { NEW, ADJUSTED, REPLACEMENT }

    public record Coverage(
            Reservation reservation,
            Strategy strategy,
            UUID supersededReservationId,
            boolean releaseSupersededAfterCommit
    ) {
        public Coverage {
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(strategy, "strategy");
            if (strategy == Strategy.REPLACEMENT && supersededReservationId == null) {
                throw new IllegalArgumentException("replacement requires supersededReservationId");
            }
            if (strategy != Strategy.REPLACEMENT && releaseSupersededAfterCommit) {
                throw new IllegalArgumentException("only replacement can require deferred release");
            }
        }
    }

    private final PaymentPort paymentPort;

    public BidReservationCoordinator(PaymentPort paymentPort) {
        this.paymentPort = Objects.requireNonNull(paymentPort);
    }

    public Coverage cover(UUID payerUserId,
                          UUID currentReservationId,
                          long targetAmountClp,
                          String idempotencyKey,
                          Instant now) {
        Objects.requireNonNull(payerUserId, "payerUserId");
        Objects.requireNonNull(now, "now");
        requirePositive(targetAmountClp);
        requireKey(idempotencyKey);

        if (currentReservationId == null) {
            Reservation created = paymentPort.reserve(
                    payerUserId,
                    targetAmountClp,
                    idempotencyKey + ":reserve",
                    now
            );
            validateCoverage(created, payerUserId, targetAmountClp);
            return new Coverage(created, Strategy.NEW, null, false);
        }

        try {
            Reservation adjusted = paymentPort.adjustReservation(
                    currentReservationId,
                    targetAmountClp,
                    idempotencyKey + ":adjust",
                    now
            );
            validateCoverage(adjusted, payerUserId, targetAmountClp);
            return new Coverage(adjusted, Strategy.ADJUSTED, null, false);
        } catch (FinanceException ex) {
            if (!"PAYMENT_PROVIDER_CAPABILITY_UNSUPPORTED".equals(ex.code())) {
                throw ex;
            }
        }

        Reservation replacement = paymentPort.reserve(
                payerUserId,
                targetAmountClp,
                idempotencyKey + ":replacement-reserve",
                now
        );
        validateCoverage(replacement, payerUserId, targetAmountClp);
        return new Coverage(replacement, Strategy.REPLACEMENT, currentReservationId, true);
    }

    public Reservation releaseSuperseded(UUID reservationId, String idempotencyKey, Instant now) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(now, "now");
        requireKey(idempotencyKey);
        return paymentPort.releaseReservation(reservationId, idempotencyKey + ":release", now);
    }

    private static void validateCoverage(Reservation reservation, UUID payerUserId, long targetAmountClp) {
        if (!payerUserId.equals(reservation.payerUserId())) {
            throw new FinanceException("PAYMENT_RESERVATION_PAYER_MISMATCH", "Reservation belongs to another payer");
        }
        if (reservation.remainingReservedClp() < targetAmountClp) {
            throw new FinanceException("PAYMENT_RESERVATION_UNDERFUNDED", "Reservation does not cover target Bid");
        }
        if (reservation.status() != PaymentPort.ReservationStatus.RESERVED) {
            throw new FinanceException("PAYMENT_RESERVATION_STATE_INVALID", "Reservation must remain RESERVED before Bid acceptance");
        }
    }

    private static void requirePositive(long amountClp) {
        if (amountClp <= 0) {
            throw new FinanceException("PAYMENT_AMOUNT_INVALID", "Bid reservation amount must be > 0");
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new FinanceException("IDEMPOTENCY_KEY_REQUIRED", "Bid reservation idempotency key is required");
        }
    }
}

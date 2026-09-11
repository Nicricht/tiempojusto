package cl.tiempojusto.finance.payment;

import java.time.Instant;
import java.util.UUID;

public interface PaymentPort {
    enum Operation { RESERVE, ADJUST_RESERVATION, RELEASE_RESERVATION, CAPTURE, REFUND, PAYOUT, OPEN_DISPUTE }
    enum FailureCode { DECLINED, TIMEOUT, PROVIDER_UNAVAILABLE, STRONG_AUTHENTICATION_REQUIRED }
    enum ReservationStatus { RESERVED, PARTIALLY_CAPTURED, CAPTURED, RELEASED }

    record Reservation(UUID id, UUID payerUserId, long authorizedAmountClp, long remainingReservedClp,
                       ReservationStatus status, Instant createdAt) {}
    record Capture(UUID id, UUID reservationId, UUID payerUserId, long amountClp,
                   long refundableRemainingClp, Instant createdAt) {}
    record Refund(UUID id, UUID captureId, UUID payerUserId, long amountClp, Instant createdAt) {}
    record PayoutTransfer(UUID id, UUID hostUserId, long amountClp, String providerReference, Instant createdAt) {}
    record PaymentDispute(UUID id, UUID captureId, long amountClp, String providerReference, Instant openedAt) {}

    Reservation reserve(UUID payerUserId, long amountClp, String idempotencyKey, Instant now);
    Reservation adjustReservation(UUID reservationId, long newAmountClp, String idempotencyKey, Instant now);
    Reservation releaseReservation(UUID reservationId, String idempotencyKey, Instant now);
    Capture capture(UUID reservationId, long amountClp, String idempotencyKey, Instant now);
    Refund refund(UUID captureId, long amountClp, String idempotencyKey, Instant now);
    PayoutTransfer payout(UUID hostUserId, long amountClp, String idempotencyKey, Instant now);
    PaymentDispute openDispute(UUID captureId, long amountClp, String providerReference, String idempotencyKey, Instant now);
}

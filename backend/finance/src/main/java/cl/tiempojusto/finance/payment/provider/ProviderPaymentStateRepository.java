package cl.tiempojusto.finance.payment.provider;

import cl.tiempojusto.finance.payment.PaymentPort.ReservationStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence seam for mapping TiempoJusto UUIDs to opaque provider object IDs.
 * Provider tokens and raw card data are intentionally absent.
 */
public interface ProviderPaymentStateRepository {

    record StoredReservation(UUID internalId, UUID payerUserId, String providerCode,
                             String providerPaymentId, long authorizedAmountClp,
                             long remainingReservedClp, ReservationStatus status,
                             Instant createdAt) {}

    record StoredCapture(UUID internalId, UUID reservationId, UUID payerUserId,
                         String providerCode, String providerPaymentId,
                         long amountClp, long refundableRemainingClp,
                         Instant createdAt) {}

    record StoredRefund(UUID internalId, UUID captureId, UUID payerUserId,
                        String providerCode, String providerRefundId,
                        long amountClp, Instant createdAt) {}

    StoredReservation saveReservation(StoredReservation reservation);
    Optional<StoredReservation> reservation(UUID internalId);
    Optional<StoredReservation> reservationByProviderPaymentId(String providerCode, String providerPaymentId);

    StoredCapture saveCapture(StoredCapture capture);
    Optional<StoredCapture> capture(UUID internalId);
    Optional<StoredCapture> captureByReservationId(UUID reservationId);

    StoredRefund saveRefund(StoredRefund refund);
    Optional<StoredRefund> refundByProviderRefundId(String providerCode, String providerRefundId);
}
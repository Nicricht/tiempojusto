package cl.tiempojusto.finance.payment.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test/dev repository. Production wiring must use durable storage. */
public final class InMemoryProviderPaymentStateRepository implements ProviderPaymentStateRepository {
    private final Map<UUID, StoredReservation> reservations = new HashMap<>();
    private final Map<String, UUID> reservationsByProvider = new HashMap<>();
    private final Map<UUID, StoredCapture> captures = new HashMap<>();
    private final Map<UUID, UUID> captureByReservation = new HashMap<>();
    private final Map<String, StoredRefund> refundsByProvider = new HashMap<>();

    @Override
    public synchronized StoredReservation saveReservation(StoredReservation reservation) {
        reservations.put(reservation.internalId(), reservation);
        reservationsByProvider.put(key(reservation.providerCode(), reservation.providerPaymentId()), reservation.internalId());
        return reservation;
    }

    @Override
    public synchronized Optional<StoredReservation> reservation(UUID internalId) {
        return Optional.ofNullable(reservations.get(internalId));
    }

    @Override
    public synchronized Optional<StoredReservation> reservationByProviderPaymentId(String providerCode, String providerPaymentId) {
        UUID id = reservationsByProvider.get(key(providerCode, providerPaymentId));
        return id == null ? Optional.empty() : Optional.ofNullable(reservations.get(id));
    }

    @Override
    public synchronized StoredCapture saveCapture(StoredCapture capture) {
        captures.put(capture.internalId(), capture);
        captureByReservation.put(capture.reservationId(), capture.internalId());
        return capture;
    }

    @Override
    public synchronized Optional<StoredCapture> capture(UUID internalId) {
        return Optional.ofNullable(captures.get(internalId));
    }

    @Override
    public synchronized Optional<StoredCapture> captureByReservationId(UUID reservationId) {
        UUID id = captureByReservation.get(reservationId);
        return id == null ? Optional.empty() : Optional.ofNullable(captures.get(id));
    }

    @Override
    public synchronized StoredRefund saveRefund(StoredRefund refund) {
        refundsByProvider.put(key(refund.providerCode(), refund.providerRefundId()), refund);
        return refund;
    }

    @Override
    public synchronized Optional<StoredRefund> refundByProviderRefundId(String providerCode, String providerRefundId) {
        return Optional.ofNullable(refundsByProvider.get(key(providerCode, providerRefundId)));
    }

    private static String key(String provider, String id) {
        return provider + "|" + id;
    }
}
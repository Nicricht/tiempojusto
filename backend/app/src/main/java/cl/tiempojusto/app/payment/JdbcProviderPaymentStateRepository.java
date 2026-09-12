package cl.tiempojusto.app.payment;

import cl.tiempojusto.finance.payment.PaymentPort.ReservationStatus;
import cl.tiempojusto.finance.payment.provider.ProviderPaymentStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProviderPaymentStateRepository implements ProviderPaymentStateRepository {
    private final JdbcTemplate jdbc;

    public JdbcProviderPaymentStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredReservation saveReservation(StoredReservation value) {
        jdbc.update("""
                INSERT INTO finance.provider_reservation_binding
                    (internal_id, payer_user_id, provider_code, provider_payment_id,
                     authorized_amount_clp, remaining_reserved_clp, reservation_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (internal_id) DO UPDATE SET
                    authorized_amount_clp = EXCLUDED.authorized_amount_clp,
                    remaining_reserved_clp = EXCLUDED.remaining_reserved_clp,
                    reservation_status = EXCLUDED.reservation_status,
                    updated_at = now()
                """,
                value.internalId(), value.payerUserId(), value.providerCode(), value.providerPaymentId(),
                value.authorizedAmountClp(), value.remainingReservedClp(), value.status().name(), value.createdAt());
        return value;
    }

    @Override
    public Optional<StoredReservation> reservation(UUID internalId) {
        return jdbc.query("""
                SELECT internal_id, payer_user_id, provider_code, provider_payment_id,
                       authorized_amount_clp, remaining_reserved_clp, reservation_status, created_at
                FROM finance.provider_reservation_binding WHERE internal_id = ?
                """, (rs, row) -> new StoredReservation(
                rs.getObject("internal_id", UUID.class), rs.getObject("payer_user_id", UUID.class),
                rs.getString("provider_code"), rs.getString("provider_payment_id"),
                rs.getLong("authorized_amount_clp"), rs.getLong("remaining_reserved_clp"),
                ReservationStatus.valueOf(rs.getString("reservation_status")),
                rs.getTimestamp("created_at").toInstant()), internalId).stream().findFirst();
    }

    @Override
    public Optional<StoredReservation> reservationByProviderPaymentId(String providerCode, String providerPaymentId) {
        return jdbc.query("""
                SELECT internal_id, payer_user_id, provider_code, provider_payment_id,
                       authorized_amount_clp, remaining_reserved_clp, reservation_status, created_at
                FROM finance.provider_reservation_binding
                WHERE provider_code = ? AND provider_payment_id = ?
                """, (rs, row) -> new StoredReservation(
                rs.getObject("internal_id", UUID.class), rs.getObject("payer_user_id", UUID.class),
                rs.getString("provider_code"), rs.getString("provider_payment_id"),
                rs.getLong("authorized_amount_clp"), rs.getLong("remaining_reserved_clp"),
                ReservationStatus.valueOf(rs.getString("reservation_status")),
                rs.getTimestamp("created_at").toInstant()), providerCode, providerPaymentId).stream().findFirst();
    }

    @Override
    public StoredCapture saveCapture(StoredCapture value) {
        jdbc.update("""
                INSERT INTO finance.provider_capture_binding
                    (internal_id, reservation_id, payer_user_id, provider_code, provider_payment_id,
                     amount_clp, refundable_remaining_clp, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (internal_id) DO UPDATE SET
                    refundable_remaining_clp = EXCLUDED.refundable_remaining_clp,
                    updated_at = now()
                """,
                value.internalId(), value.reservationId(), value.payerUserId(), value.providerCode(),
                value.providerPaymentId(), value.amountClp(), value.refundableRemainingClp(), value.createdAt());
        return value;
    }

    @Override
    public Optional<StoredCapture> capture(UUID internalId) {
        return jdbc.query("""
                SELECT internal_id, reservation_id, payer_user_id, provider_code, provider_payment_id,
                       amount_clp, refundable_remaining_clp, created_at
                FROM finance.provider_capture_binding WHERE internal_id = ?
                """, (rs, row) -> capture(rs), internalId).stream().findFirst();
    }

    @Override
    public Optional<StoredCapture> captureByReservationId(UUID reservationId) {
        return jdbc.query("""
                SELECT internal_id, reservation_id, payer_user_id, provider_code, provider_payment_id,
                       amount_clp, refundable_remaining_clp, created_at
                FROM finance.provider_capture_binding WHERE reservation_id = ?
                """, (rs, row) -> capture(rs), reservationId).stream().findFirst();
    }

    @Override
    public StoredRefund saveRefund(StoredRefund value) {
        jdbc.update("""
                INSERT INTO finance.provider_refund_binding
                    (internal_id, capture_id, payer_user_id, provider_code, provider_refund_id, amount_clp, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (internal_id) DO NOTHING
                """,
                value.internalId(), value.captureId(), value.payerUserId(), value.providerCode(),
                value.providerRefundId(), value.amountClp(), value.createdAt());
        return value;
    }

    @Override
    public Optional<StoredRefund> refundByProviderRefundId(String providerCode, String providerRefundId) {
        return jdbc.query("""
                SELECT internal_id, capture_id, payer_user_id, provider_code, provider_refund_id, amount_clp, created_at
                FROM finance.provider_refund_binding
                WHERE provider_code = ? AND provider_refund_id = ?
                """, (rs, row) -> new StoredRefund(
                rs.getObject("internal_id", UUID.class), rs.getObject("capture_id", UUID.class),
                rs.getObject("payer_user_id", UUID.class), rs.getString("provider_code"),
                rs.getString("provider_refund_id"), rs.getLong("amount_clp"),
                rs.getTimestamp("created_at").toInstant()), providerCode, providerRefundId).stream().findFirst();
    }

    private static StoredCapture capture(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredCapture(
                rs.getObject("internal_id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getObject("payer_user_id", UUID.class), rs.getString("provider_code"),
                rs.getString("provider_payment_id"), rs.getLong("amount_clp"),
                rs.getLong("refundable_remaining_clp"), rs.getTimestamp("created_at").toInstant());
    }
}

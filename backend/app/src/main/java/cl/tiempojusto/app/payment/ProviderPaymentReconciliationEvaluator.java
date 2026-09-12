package cl.tiempojusto.app.payment;

import java.util.Locale;
import java.util.UUID;

/**
 * Pure reconciliation policy. It never mutates ledger or provider state.
 */
public final class ProviderPaymentReconciliationEvaluator {
    private ProviderPaymentReconciliationEvaluator() {}

    public static Decision evaluate(MercadoPagoPaymentQueryClient.Snapshot provider, LocalState local) {
        if (local == null) {
            return new Decision(Result.UNBOUND, "Provider payment has no TiempoJusto reservation binding");
        }

        String status = provider.status().toLowerCase(Locale.ROOT);
        if ("authorized".equals(status)) {
            if (local.localAuthorizedClp() != provider.amountClp()) {
                return mismatch("Authorized amount differs from local reservation");
            }
            if (!"RESERVED".equals(local.reservationStatus())) {
                return pending("Provider is authorized while local reservation transition is not committed yet");
            }
            return matched("Provider authorization matches RESERVED local coverage");
        }

        if ("approved".equals(status)) {
            if (local.localCapturedClp() == null) {
                return pending("Provider is captured but local capture binding is not committed yet");
            }
            if (local.localCapturedClp() != provider.amountClp()) {
                return mismatch("Captured amount differs from provider transaction amount");
            }
            if (local.localRefundedClp() > provider.refundedClp()) {
                return mismatch("Local refunded amount exceeds provider refunded amount");
            }
            if (local.localRefundedClp() < provider.refundedClp()) {
                return pending("Provider refund is ahead of local refund binding");
            }
            return matched(provider.refundedClp() > 0
                    ? "Capture and refund totals match provider"
                    : "Captured payment matches local capture binding");
        }

        if ("cancelled".equals(status) || "canceled".equals(status)) {
            if ("RELEASED".equals(local.reservationStatus())) {
                return matched("Provider cancellation matches released local reservation");
            }
            return pending("Provider cancellation is ahead of local release commit");
        }

        if ("refunded".equals(status) || "charged_back".equals(status)) {
            if (local.localCapturedClp() == null) {
                return pending("Provider refund/chargeback arrived before local capture binding");
            }
            if (provider.refundedClp() > 0 && local.localRefundedClp() < provider.refundedClp()) {
                return pending("Provider refund total is ahead of local refund bindings");
            }
            if (provider.refundedClp() > 0 && local.localRefundedClp() > provider.refundedClp()) {
                return mismatch("Local refund total exceeds provider refund total");
            }
            return new Decision(Result.OBSERVED,
                    "External refund/chargeback observed; ledger mutation requires a separate audited domain flow");
        }

        return new Decision(Result.OBSERVED, "Provider state observed without authoritative local mutation: " + status);
    }

    private static Decision matched(String reason) { return new Decision(Result.MATCHED, reason); }
    private static Decision mismatch(String reason) { return new Decision(Result.MISMATCH, reason); }
    private static Decision pending(String reason) { return new Decision(Result.PENDING_LOCAL_COMMIT, reason); }

    public enum Result { MATCHED, MISMATCH, UNBOUND, PENDING_LOCAL_COMMIT, OBSERVED }

    public record Decision(Result result, String reason) {}

    public record LocalState(UUID reservationId, long localAuthorizedClp, String reservationStatus,
                             UUID captureId, Long localCapturedClp, long localRefundedClp) {}
}

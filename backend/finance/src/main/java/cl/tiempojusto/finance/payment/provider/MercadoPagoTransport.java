package cl.tiempojusto.finance.payment.provider;

import cl.tiempojusto.finance.payment.provider.PaymentInstrumentResolver.OpaquePaymentInstrument;

import java.time.Instant;

/**
 * Network seam for Mercado Pago. Implementations are responsible for sending
 * credentials through secure configuration and propagating X-Idempotency-Key.
 */
public interface MercadoPagoTransport {
    enum ProviderStatus { AUTHORIZED, CAPTURED, CANCELLED, REFUNDED, PENDING, REJECTED }

    record AuthorizationResult(String providerPaymentId, ProviderStatus status,
                               long authorizedAmountClp, Instant createdAt) {}
    record CaptureResult(String providerPaymentId, ProviderStatus status,
                         long capturedAmountClp, Instant capturedAt) {}
    record CancelResult(String providerPaymentId, ProviderStatus status, Instant cancelledAt) {}
    record RefundResult(String providerPaymentId, String providerRefundId,
                        ProviderStatus status, long refundedAmountClp, Instant refundedAt) {}

    AuthorizationResult authorize(OpaquePaymentInstrument instrument, long amountClp,
                                  String idempotencyKey, Instant now);

    CaptureResult capture(String providerPaymentId, long amountClp,
                          String idempotencyKey, Instant now);

    CancelResult cancel(String providerPaymentId, String idempotencyKey, Instant now);

    RefundResult refund(String providerPaymentId, long amountClp,
                        String idempotencyKey, Instant now);
}
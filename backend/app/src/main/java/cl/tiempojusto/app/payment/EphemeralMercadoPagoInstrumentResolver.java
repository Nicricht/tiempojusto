package cl.tiempojusto.app.payment;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.provider.MercadoPagoPaymentPort;
import cl.tiempojusto.finance.payment.provider.PaymentInstrumentResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, in-memory token handoff for sandbox/protected runtime use.
 * Tokens are consumed once and are never persisted.
 */
public final class EphemeralMercadoPagoInstrumentResolver implements PaymentInstrumentResolver {
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public EphemeralMercadoPagoInstrumentResolver(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("instrument ttl must be positive");
        }
        this.ttl = ttl;
    }

    public Registered register(UUID payerUserId, String providerToken, String payerEmail, String paymentMethodId) {
        if (payerUserId == null) throw new IllegalArgumentException("payerUserId is required");
        Instant expiresAt = Instant.now().plus(ttl);
        OpaquePaymentInstrument instrument = new OpaquePaymentInstrument(
                MercadoPagoPaymentPort.PROVIDER, providerToken, payerEmail, paymentMethodId);
        entries.put(payerUserId, new Entry(instrument, expiresAt));
        return new Registered(expiresAt);
    }

    @Override
    public OpaquePaymentInstrument resolve(UUID payerUserId) {
        Entry entry = entries.remove(payerUserId);
        if (entry == null) {
            throw new FinanceException("PAYMENT_INSTRUMENT_REQUIRED",
                    "No ephemeral Mercado Pago payment instrument is registered for payer");
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            throw new FinanceException("PAYMENT_INSTRUMENT_EXPIRED",
                    "Ephemeral Mercado Pago payment instrument expired");
        }
        return entry.instrument();
    }

    private record Entry(OpaquePaymentInstrument instrument, Instant expiresAt) {}
    public record Registered(Instant expiresAt) {}
}

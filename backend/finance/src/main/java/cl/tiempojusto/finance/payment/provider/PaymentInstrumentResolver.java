package cl.tiempojusto.finance.payment.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * Resolves a provider-tokenized payment instrument for the payer. The token is
 * opaque and ephemeral. PAN/CVV must never enter this module.
 */
public interface PaymentInstrumentResolver {
    OpaquePaymentInstrument resolve(UUID payerUserId);

    final class OpaquePaymentInstrument {
        private final String providerCode;
        private final String providerToken;
        private final String payerEmail;
        private final String paymentMethodId;

        public OpaquePaymentInstrument(String providerCode, String providerToken,
                                       String payerEmail, String paymentMethodId) {
            this.providerCode = required(providerCode, "providerCode");
            this.providerToken = required(providerToken, "providerToken");
            this.payerEmail = required(payerEmail, "payerEmail");
            this.paymentMethodId = required(paymentMethodId, "paymentMethodId");
        }

        public String providerCode() { return providerCode; }
        public String providerToken() { return providerToken; }
        public String payerEmail() { return payerEmail; }
        public String paymentMethodId() { return paymentMethodId; }

        @Override
        public String toString() {
            return "OpaquePaymentInstrument[providerCode=" + providerCode + ", providerToken=<redacted>, payerEmail=<redacted>, paymentMethodId=" + paymentMethodId + "]";
        }

        private static String required(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
            return value;
        }
    }
}
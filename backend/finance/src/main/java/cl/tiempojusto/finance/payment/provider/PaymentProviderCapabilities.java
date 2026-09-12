package cl.tiempojusto.finance.payment.provider;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.PaymentPort.Operation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit provider capability declaration. A real adapter must fail closed
 * whenever the provider cannot preserve the PaymentPort/V1.7 semantics.
 */
public record PaymentProviderCapabilities(
        String providerCode,
        Set<Operation> supportedOperations,
        boolean singleFinalPartialCapture,
        boolean automaticMarketplaceSplit,
        boolean controlledPayoutHold
) {
    public PaymentProviderCapabilities {
        if (providerCode == null || providerCode.isBlank()) {
            throw new IllegalArgumentException("providerCode is required");
        }
        Objects.requireNonNull(supportedOperations, "supportedOperations");
        supportedOperations = supportedOperations.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(supportedOperations));
    }

    public boolean supports(Operation operation) {
        return supportedOperations.contains(Objects.requireNonNull(operation));
    }

    public void require(Operation operation) {
        if (!supports(operation)) {
            throw new FinanceException(
                    "PAYMENT_PROVIDER_CAPABILITY_UNSUPPORTED",
                    providerCode + " does not support TiempoJusto operation " + operation
            );
        }
    }

    /**
     * Candidate profile backed by Mercado Pago Payments/Checkout Bricks manual
     * authorization semantics. It deliberately excludes payout, reservation
     * adjustment and remote dispute creation until their product semantics are
     * proven compatible with TiempoJusto V1.7.
     */
    public static PaymentProviderCapabilities mercadoPagoPaymentsV1Candidate() {
        return new PaymentProviderCapabilities(
                "MERCADO_PAGO",
                EnumSet.of(Operation.RESERVE, Operation.RELEASE_RESERVATION, Operation.CAPTURE, Operation.REFUND),
                true,
                false,
                false
        );
    }
}
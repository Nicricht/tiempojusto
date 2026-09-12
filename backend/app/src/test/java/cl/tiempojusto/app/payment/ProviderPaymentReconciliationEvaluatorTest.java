package cl.tiempojusto.app.payment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderPaymentReconciliationEvaluatorTest {

    @Test
    void authorizedReservationMatchesOnlyWhenAmountAndStateMatch() {
        var provider = new MercadoPagoPaymentQueryClient.Snapshot("p1", "authorized", 55_000, 0, false);
        var local = new ProviderPaymentReconciliationEvaluator.LocalState(
                UUID.randomUUID(), 55_000, "RESERVED", null, null, 0);

        var decision = ProviderPaymentReconciliationEvaluator.evaluate(provider, local);
        assertEquals(ProviderPaymentReconciliationEvaluator.Result.MATCHED, decision.result());
    }

    @Test
    void capturedProviderWithoutLocalCaptureWaitsForCommit() {
        var provider = new MercadoPagoPaymentQueryClient.Snapshot("p2", "approved", 100_000, 0, true);
        var local = new ProviderPaymentReconciliationEvaluator.LocalState(
                UUID.randomUUID(), 100_000, "RESERVED", null, null, 0);

        var decision = ProviderPaymentReconciliationEvaluator.evaluate(provider, local);
        assertEquals(ProviderPaymentReconciliationEvaluator.Result.PENDING_LOCAL_COMMIT, decision.result());
    }

    @Test
    void amountMismatchIsNeverSilentlyAccepted() {
        var provider = new MercadoPagoPaymentQueryClient.Snapshot("p3", "approved", 90_000, 0, true);
        var local = new ProviderPaymentReconciliationEvaluator.LocalState(
                UUID.randomUUID(), 100_000, "CAPTURED", UUID.randomUUID(), 100_000L, 0);

        var decision = ProviderPaymentReconciliationEvaluator.evaluate(provider, local);
        assertEquals(ProviderPaymentReconciliationEvaluator.Result.MISMATCH, decision.result());
    }

    @Test
    void providerRefundAheadOfLocalBindingWaitsInsteadOfMutatingLedger() {
        var provider = new MercadoPagoPaymentQueryClient.Snapshot("p4", "approved", 100_000, 20_000, true);
        var local = new ProviderPaymentReconciliationEvaluator.LocalState(
                UUID.randomUUID(), 100_000, "CAPTURED", UUID.randomUUID(), 100_000L, 0);

        var decision = ProviderPaymentReconciliationEvaluator.evaluate(provider, local);
        assertEquals(ProviderPaymentReconciliationEvaluator.Result.PENDING_LOCAL_COMMIT, decision.result());
    }

    @Test
    void unknownProviderPaymentIsUnboundNotAutoImported() {
        var provider = new MercadoPagoPaymentQueryClient.Snapshot("p5", "approved", 10_000, 0, true);
        var decision = ProviderPaymentReconciliationEvaluator.evaluate(provider, null);
        assertEquals(ProviderPaymentReconciliationEvaluator.Result.UNBOUND, decision.result());
    }
}

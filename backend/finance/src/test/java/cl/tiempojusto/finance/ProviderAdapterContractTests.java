package cl.tiempojusto.finance;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.PaymentPort.*;
import cl.tiempojusto.finance.payment.provider.*;
import cl.tiempojusto.finance.payment.provider.MercadoPagoTransport.*;
import cl.tiempojusto.finance.payment.provider.PaymentInstrumentResolver.OpaquePaymentInstrument;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ProviderAdapterContractTests {
    private static final Instant T0 = Instant.parse("2026-09-11T12:00:00Z");
    private static int passed;

    public static void main(String[] args) {
        test("capabilities fail closed", ProviderAdapterContractTests::capabilitiesFailClosed);
        test("opaque instrument redacts secrets", ProviderAdapterContractTests::opaqueInstrumentRedacts);
        test("reserve binds provider payment id", ProviderAdapterContractTests::reserveBindsProviderPayment);
        test("reserve retry reuses internal id", ProviderAdapterContractTests::reserveRetryIsStable);
        test("reservation adjustment is blocked", ProviderAdapterContractTests::adjustBlocked);
        test("single final partial capture", ProviderAdapterContractTests::singleFinalPartialCapture);
        test("release cancels open authorization", ProviderAdapterContractTests::releaseCancels);
        test("refund is provider idempotent", ProviderAdapterContractTests::refundIdempotent);
        test("payout is disabled", ProviderAdapterContractTests::payoutDisabled);
        test("provider rejection is explicit", ProviderAdapterContractTests::providerRejection);
        System.out.println("\nPASS: " + passed + " provider adapter contract tests");
    }

    private static void capabilitiesFailClosed() {
        var c = PaymentProviderCapabilities.mercadoPagoPaymentsV1Candidate();
        isTrue(c.supports(Operation.RESERVE));
        isTrue(c.supports(Operation.CAPTURE));
        isFalse(c.supports(Operation.ADJUST_RESERVATION));
        isFalse(c.supports(Operation.PAYOUT));
        isFalse(c.controlledPayoutHold());
        isFalse(c.automaticMarketplaceSplit());
    }

    private static void opaqueInstrumentRedacts() {
        OpaquePaymentInstrument instrument = new OpaquePaymentInstrument(
                "MERCADO_PAGO", "tok_super_secret", "payer@example.test", "visa");
        String rendered = instrument.toString();
        isFalse(rendered.contains("tok_super_secret"));
        isFalse(rendered.contains("payer@example.test"));
    }

    private static void reserveBindsProviderPayment() {
        Fixture f = fixture();
        Reservation r = f.port.reserve(f.payer, 50_000, "reserve-1", T0);
        eq(50_000L, r.remainingReservedClp());
        eq(ReservationStatus.RESERVED, r.status());
        isTrue(f.repo.reservation(r.id()).isPresent());
    }

    private static void reserveRetryIsStable() {
        Fixture f = fixture();
        Reservation a = f.port.reserve(f.payer, 50_000, "same-key", T0);
        Reservation b = f.port.reserve(f.payer, 50_000, "same-key", T0.plusSeconds(1));
        eq(a.id(), b.id());
        eq(1, f.transport.authorizationCount);
    }

    private static void adjustBlocked() {
        Fixture f = fixture();
        Reservation r = f.port.reserve(f.payer, 50_000, "r", T0);
        expect("PAYMENT_PROVIDER_CAPABILITY_UNSUPPORTED",
                () -> f.port.adjustReservation(r.id(), 60_000, "up", T0));
    }

    private static void singleFinalPartialCapture() {
        Fixture f = fixture();
        Reservation r = f.port.reserve(f.payer, 80_000, "r", T0);
        Capture c = f.port.capture(r.id(), 30_000, "c", T0.plusSeconds(2));
        eq(30_000L, c.amountClp());
        Reservation stored = f.port.releaseReservation(r.id(), "release-unused", T0.plusSeconds(3));
        eq(ReservationStatus.CAPTURED, stored.status());
        eq(0L, stored.remainingReservedClp());
        expect("PAYMENT_MULTIPLE_CAPTURE_UNSUPPORTED",
                () -> f.port.capture(r.id(), 10_000, "c2", T0.plusSeconds(4)));
    }

    private static void releaseCancels() {
        Fixture f = fixture();
        Reservation r = f.port.reserve(f.payer, 40_000, "r", T0);
        Reservation released = f.port.releaseReservation(r.id(), "cancel", T0.plusSeconds(1));
        eq(ReservationStatus.RELEASED, released.status());
        eq(1, f.transport.cancelCount);
    }

    private static void refundIdempotent() {
        Fixture f = fixture();
        Reservation r = f.port.reserve(f.payer, 50_000, "r", T0);
        Capture c = f.port.capture(r.id(), 50_000, "c", T0.plusSeconds(1));
        Refund a = f.port.refund(c.id(), 10_000, "rf", T0.plusSeconds(2));
        Refund b = f.port.refund(c.id(), 10_000, "rf", T0.plusSeconds(3));
        eq(a.id(), b.id());
        eq(1, f.transport.refundCount);
    }

    private static void payoutDisabled() {
        Fixture f = fixture();
        expect("PAYMENT_PROVIDER_CAPABILITY_UNSUPPORTED",
                () -> f.port.payout(UUID.randomUUID(), 10_000, "p", T0));
    }

    private static void providerRejection() {
        Fixture f = fixture();
        f.transport.rejectAuthorization = true;
        expect("PAYMENT_DECLINED", () -> f.port.reserve(f.payer, 20_000, "r", T0));
    }

    private static Fixture fixture() {
        UUID payer = UUID.randomUUID();
        FakeTransport transport = new FakeTransport();
        InMemoryProviderPaymentStateRepository repo = new InMemoryProviderPaymentStateRepository();
        PaymentInstrumentResolver resolver = userId -> new OpaquePaymentInstrument(
                "MERCADO_PAGO", "tok_opaque_" + userId, "payer@example.test", "visa");
        return new Fixture(payer, transport, repo, new MercadoPagoPaymentPort(transport, resolver, repo));
    }

    private static void test(String name, Runnable body) {
        try { body.run(); passed++; System.out.println("PASS  " + name); }
        catch (Throwable t) { System.err.println("FAIL  " + name + " -> " + t); throw t; }
    }

    private static void expect(String code, Runnable body) {
        try { body.run(); throw new AssertionError("Expected " + code); }
        catch (FinanceException e) {
            if (!code.equals(e.code())) throw new AssertionError("Expected " + code + " but got " + e.code(), e);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void eq(long expected, long actual) { if (expected != actual) throw new AssertionError("Expected " + expected + " but got " + actual); }
    private static void eq(int expected, int actual) { if (expected != actual) throw new AssertionError("Expected " + expected + " but got " + actual); }
    private static void isTrue(boolean value) { if (!value) throw new AssertionError("Expected true"); }
    private static void isFalse(boolean value) { if (value) throw new AssertionError("Expected false"); }

    private record Fixture(UUID payer, FakeTransport transport,
                           InMemoryProviderPaymentStateRepository repo,
                           MercadoPagoPaymentPort port) {}

    private static final class FakeTransport implements MercadoPagoTransport {
        private final Map<String, AuthorizationResult> authorizations = new HashMap<>();
        private final Map<String, CaptureResult> captures = new HashMap<>();
        private final Map<String, RefundResult> refunds = new HashMap<>();
        private int sequence;
        int authorizationCount;
        int cancelCount;
        int refundCount;
        boolean rejectAuthorization;

        @Override
        public AuthorizationResult authorize(OpaquePaymentInstrument instrument, long amountClp,
                                             String idempotencyKey, Instant now) {
            AuthorizationResult prior = authorizations.get(idempotencyKey);
            if (prior != null) return prior;
            authorizationCount++;
            ProviderStatus status = rejectAuthorization ? ProviderStatus.REJECTED : ProviderStatus.AUTHORIZED;
            AuthorizationResult result = new AuthorizationResult("mp-pay-" + (++sequence), status, amountClp, now);
            authorizations.put(idempotencyKey, result);
            return result;
        }

        @Override
        public CaptureResult capture(String providerPaymentId, long amountClp, String idempotencyKey, Instant now) {
            CaptureResult prior = captures.get(idempotencyKey);
            if (prior != null) return prior;
            CaptureResult result = new CaptureResult(providerPaymentId, ProviderStatus.CAPTURED, amountClp, now);
            captures.put(idempotencyKey, result);
            return result;
        }

        @Override
        public CancelResult cancel(String providerPaymentId, String idempotencyKey, Instant now) {
            cancelCount++;
            return new CancelResult(providerPaymentId, ProviderStatus.CANCELLED, now);
        }

        @Override
        public RefundResult refund(String providerPaymentId, long amountClp, String idempotencyKey, Instant now) {
            RefundResult prior = refunds.get(idempotencyKey);
            if (prior != null) return prior;
            refundCount++;
            RefundResult result = new RefundResult(providerPaymentId, "mp-refund-" + (++sequence), ProviderStatus.REFUNDED, amountClp, now);
            refunds.put(idempotencyKey, result);
            return result;
        }
    }
}
package cl.tiempojusto.finance.payment.provider;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.finance.payment.provider.MercadoPagoTransport.ProviderStatus;
import cl.tiempojusto.finance.payment.provider.ProviderPaymentStateRepository.StoredCapture;
import cl.tiempojusto.finance.payment.provider.ProviderPaymentStateRepository.StoredRefund;
import cl.tiempojusto.finance.payment.provider.ProviderPaymentStateRepository.StoredReservation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mercado Pago authorization/capture/refund candidate adapter for Chile.
 *
 * It is intentionally fail-closed for provider-side reservation adjustment,
 * payout and dispute creation. Those operations are not enabled until a
 * provider contract can preserve the exact TiempoJusto V1.7 semantics.
 *
 * The adapter never receives PAN/CVV. It consumes only an opaque token created
 * by provider-side tokenization in the client payment flow.
 */
public final class MercadoPagoPaymentPort implements PaymentPort {
    public static final String PROVIDER = "MERCADO_PAGO";

    private final MercadoPagoTransport transport;
    private final PaymentInstrumentResolver instrumentResolver;
    private final ProviderPaymentStateRepository repository;
    private final PaymentProviderCapabilities capabilities;

    public MercadoPagoPaymentPort(MercadoPagoTransport transport,
                                  PaymentInstrumentResolver instrumentResolver,
                                  ProviderPaymentStateRepository repository) {
        this.transport = Objects.requireNonNull(transport);
        this.instrumentResolver = Objects.requireNonNull(instrumentResolver);
        this.repository = Objects.requireNonNull(repository);
        this.capabilities = PaymentProviderCapabilities.mercadoPagoPaymentsV1Candidate();
    }

    public PaymentProviderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public synchronized Reservation reserve(UUID payerUserId, long amountClp, String idempotencyKey, Instant now) {
        requirePositive(amountClp, "amountClp");
        requireKey(idempotencyKey);
        Objects.requireNonNull(payerUserId, "payerUserId");
        Objects.requireNonNull(now, "now");
        capabilities.require(Operation.RESERVE);

        var instrument = instrumentResolver.resolve(payerUserId);
        if (!PROVIDER.equals(instrument.providerCode())) {
            throw error("PAYMENT_INSTRUMENT_PROVIDER_MISMATCH", "Expected a Mercado Pago tokenized instrument");
        }

        MercadoPagoTransport.AuthorizationResult result =
                transport.authorize(instrument, amountClp, idempotencyKey, now);
        requireProviderState(result.status(), ProviderStatus.AUTHORIZED, "authorization");
        if (result.authorizedAmountClp() != amountClp) {
            throw error("PAYMENT_PROVIDER_AMOUNT_MISMATCH", "Provider authorized a different amount");
        }

        StoredReservation existing = repository
                .reservationByProviderPaymentId(PROVIDER, required(result.providerPaymentId(), "providerPaymentId"))
                .orElse(null);
        if (existing != null) {
            if (!existing.payerUserId().equals(payerUserId) || existing.authorizedAmountClp() != amountClp) {
                throw error("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", "Provider payment is already bound to a different reservation payload");
            }
            return toReservation(existing);
        }

        StoredReservation stored = new StoredReservation(
                UUID.randomUUID(), payerUserId, PROVIDER, result.providerPaymentId(),
                amountClp, amountClp, ReservationStatus.RESERVED,
                result.createdAt() == null ? now : result.createdAt()
        );
        repository.saveReservation(stored);
        return toReservation(stored);
    }

    @Override
    public synchronized Reservation adjustReservation(UUID reservationId, long newAmountClp,
                                                      String idempotencyKey, Instant now) {
        requirePositive(newAmountClp, "newAmountClp");
        requireKey(idempotencyKey);
        StoredReservation current = requiredReservation(reservationId);
        if (newAmountClp == current.authorizedAmountClp()) return toReservation(current);
        capabilities.require(Operation.ADJUST_RESERVATION);
        throw new AssertionError("unreachable");
    }

    @Override
    public synchronized Reservation releaseReservation(UUID reservationId, String idempotencyKey, Instant now) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        capabilities.require(Operation.RELEASE_RESERVATION);
        StoredReservation current = requiredReservation(reservationId);

        if (current.status() == ReservationStatus.RELEASED ||
                current.status() == ReservationStatus.CAPTURED ||
                current.remainingReservedClp() == 0) {
            return toReservation(current);
        }

        MercadoPagoTransport.CancelResult result =
                transport.cancel(current.providerPaymentId(), idempotencyKey, now);
        requireProviderState(result.status(), ProviderStatus.CANCELLED, "cancellation");

        StoredReservation released = new StoredReservation(
                current.internalId(), current.payerUserId(), current.providerCode(), current.providerPaymentId(),
                current.authorizedAmountClp(), 0L, ReservationStatus.RELEASED, current.createdAt()
        );
        repository.saveReservation(released);
        return toReservation(released);
    }

    @Override
    public synchronized Capture capture(UUID reservationId, long amountClp, String idempotencyKey, Instant now) {
        requirePositive(amountClp, "amountClp");
        requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        capabilities.require(Operation.CAPTURE);

        StoredReservation current = requiredReservation(reservationId);
        if (current.status() == ReservationStatus.RELEASED) {
            throw error("PAYMENT_RESERVATION_RELEASED", "Reservation is released");
        }

        StoredCapture prior = repository.captureByReservationId(reservationId).orElse(null);
        if (prior != null) {
            if (prior.amountClp() != amountClp) {
                throw error("PAYMENT_MULTIPLE_CAPTURE_UNSUPPORTED", "Mercado Pago candidate uses one final capture per authorization");
            }
            return toCapture(prior);
        }

        if (amountClp > current.remainingReservedClp()) {
            throw error("PAYMENT_CAPTURE_EXCEEDS_RESERVATION", "Capture exceeds provider authorization");
        }

        MercadoPagoTransport.CaptureResult result =
                transport.capture(current.providerPaymentId(), amountClp, idempotencyKey, now);
        requireProviderState(result.status(), ProviderStatus.CAPTURED, "capture");
        if (result.capturedAmountClp() != amountClp) {
            throw error("PAYMENT_PROVIDER_AMOUNT_MISMATCH", "Provider captured a different amount");
        }

        StoredCapture capture = new StoredCapture(
                UUID.randomUUID(), current.internalId(), current.payerUserId(), PROVIDER,
                current.providerPaymentId(), amountClp, amountClp,
                result.capturedAt() == null ? now : result.capturedAt()
        );
        repository.saveCapture(capture);

        repository.saveReservation(new StoredReservation(
                current.internalId(), current.payerUserId(), current.providerCode(), current.providerPaymentId(),
                current.authorizedAmountClp(), 0L, ReservationStatus.CAPTURED, current.createdAt()
        ));
        return toCapture(capture);
    }

    @Override
    public synchronized Refund refund(UUID captureId, long amountClp, String idempotencyKey, Instant now) {
        requirePositive(amountClp, "amountClp");
        requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        capabilities.require(Operation.REFUND);
        StoredCapture current = requiredCapture(captureId);
        if (amountClp > current.refundableRemainingClp()) {
            throw error("PAYMENT_REFUND_EXCEEDS_CAPTURE", "Refund exceeds refundable amount");
        }

        MercadoPagoTransport.RefundResult result =
                transport.refund(current.providerPaymentId(), amountClp, idempotencyKey, now);
        requireProviderState(result.status(), ProviderStatus.REFUNDED, "refund");
        if (result.refundedAmountClp() != amountClp) {
            throw error("PAYMENT_PROVIDER_AMOUNT_MISMATCH", "Provider refunded a different amount");
        }

        String providerRefundId = required(result.providerRefundId(), "providerRefundId");
        StoredRefund prior = repository.refundByProviderRefundId(PROVIDER, providerRefundId).orElse(null);
        if (prior != null) {
            if (!prior.captureId().equals(captureId) || prior.amountClp() != amountClp) {
                throw error("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", "Provider refund is already bound to another payload");
            }
            return toRefund(prior);
        }

        StoredRefund refund = new StoredRefund(
                UUID.randomUUID(), captureId, current.payerUserId(), PROVIDER,
                providerRefundId, amountClp, result.refundedAt() == null ? now : result.refundedAt()
        );
        repository.saveRefund(refund);
        repository.saveCapture(new StoredCapture(
                current.internalId(), current.reservationId(), current.payerUserId(), current.providerCode(),
                current.providerPaymentId(), current.amountClp(), current.refundableRemainingClp() - amountClp,
                current.createdAt()
        ));
        return toRefund(refund);
    }

    @Override
    public PayoutTransfer payout(UUID hostUserId, long amountClp, String idempotencyKey, Instant now) {
        capabilities.require(Operation.PAYOUT);
        throw new AssertionError("unreachable");
    }

    @Override
    public PaymentDispute openDispute(UUID captureId, long amountClp, String providerReference,
                                      String idempotencyKey, Instant now) {
        capabilities.require(Operation.OPEN_DISPUTE);
        throw new AssertionError("unreachable");
    }

    private StoredReservation requiredReservation(UUID id) {
        return repository.reservation(Objects.requireNonNull(id))
                .orElseThrow(() -> error("PAYMENT_RESERVATION_NOT_FOUND", "Reservation does not exist"));
    }

    private StoredCapture requiredCapture(UUID id) {
        return repository.capture(Objects.requireNonNull(id))
                .orElseThrow(() -> error("PAYMENT_CAPTURE_NOT_FOUND", "Capture does not exist"));
    }

    private static Reservation toReservation(StoredReservation value) {
        return new Reservation(value.internalId(), value.payerUserId(), value.authorizedAmountClp(),
                value.remainingReservedClp(), value.status(), value.createdAt());
    }

    private static Capture toCapture(StoredCapture value) {
        return new Capture(value.internalId(), value.reservationId(), value.payerUserId(), value.amountClp(),
                value.refundableRemainingClp(), value.createdAt());
    }

    private static Refund toRefund(StoredRefund value) {
        return new Refund(value.internalId(), value.captureId(), value.payerUserId(), value.amountClp(), value.createdAt());
    }

    private static void requireProviderState(ProviderStatus actual, ProviderStatus expected, String operation) {
        if (actual == expected) return;
        if (actual == ProviderStatus.REJECTED) {
            throw error("PAYMENT_DECLINED", "Provider rejected " + operation);
        }
        if (actual == ProviderStatus.PENDING) {
            throw error("PAYMENT_PROVIDER_PENDING", "Provider left " + operation + " pending");
        }
        throw error("PAYMENT_PROVIDER_STATE_INVALID", "Unexpected provider state for " + operation + ": " + actual);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw error("PAYMENT_AMOUNT_INVALID", name + " must be > 0");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency key is required");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw error("PAYMENT_PROVIDER_RESPONSE_INVALID", name + " is required");
        return value;
    }

    private static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }
}
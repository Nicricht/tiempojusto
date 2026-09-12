package cl.tiempojusto.app.api;

import cl.tiempojusto.app.payment.EphemeralMercadoPagoInstrumentResolver;
import cl.tiempojusto.finance.payment.PaymentPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/**
 * Explicitly gated provider-sandbox probe. It is not a product payment API.
 * The provider token is handed to the one-time in-memory resolver and is never
 * persisted or echoed in the response.
 */
@RestController
@RequestMapping("/internal/payment-sandbox")
@ConditionalOnProperty(name = "tiempojusto.payment.sandbox-probe-enabled", havingValue = "true")
public class PaymentSandboxProbeController {
    private final PaymentPort payments;
    private final EphemeralMercadoPagoInstrumentResolver instruments;
    private final byte[] probeKey;

    public PaymentSandboxProbeController(
            PaymentPort payments,
            EphemeralMercadoPagoInstrumentResolver instruments,
            @Value("${tiempojusto.payment.sandbox-probe-key:}") String probeKey) {
        if (probeKey == null || probeKey.isBlank()) {
            throw new IllegalArgumentException("sandbox probe key is required when probe is enabled");
        }
        this.payments = payments;
        this.instruments = instruments;
        this.probeKey = probeKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/full-cycle")
    public ResponseEntity<FullCycleResult> fullCycle(
            @RequestHeader(value = "X-TJ-Sandbox-Probe-Key", required = false) String suppliedKey,
            @RequestBody ProbeRequest body) {
        requireProbeKey(suppliedKey);
        validate(body);
        String key = "sandbox-probe:" + UUID.randomUUID();
        instruments.register(body.payerUserId(), body.providerToken(), body.payerEmail(), body.paymentMethodId());
        var reservation = payments.reserve(body.payerUserId(), body.amountClp(), key + ":reserve", Instant.now());
        var capture = payments.capture(reservation.id(), body.amountClp(), key + ":capture", Instant.now());
        var refund = payments.refund(capture.id(), body.amountClp(), key + ":refund", Instant.now());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new FullCycleResult(reservation.id(), reservation.status().name(), capture.id(), refund.id(),
                        body.amountClp(), "REFUNDED"));
    }

    @PostMapping("/reserve-release")
    public ResponseEntity<ReserveReleaseResult> reserveRelease(
            @RequestHeader(value = "X-TJ-Sandbox-Probe-Key", required = false) String suppliedKey,
            @RequestBody ProbeRequest body) {
        requireProbeKey(suppliedKey);
        validate(body);
        String key = "sandbox-probe:" + UUID.randomUUID();
        instruments.register(body.payerUserId(), body.providerToken(), body.payerEmail(), body.paymentMethodId());
        var reservation = payments.reserve(body.payerUserId(), body.amountClp(), key + ":reserve", Instant.now());
        var released = payments.releaseReservation(reservation.id(), key + ":release", Instant.now());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ReserveReleaseResult(reservation.id(), released.status().name(), body.amountClp()));
    }

    private void requireProbeKey(String supplied) {
        byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(probeKey, candidate)) {
            throw ApiProblem.unauthorized("PAYMENT_SANDBOX_PROBE_UNAUTHORIZED", "Sandbox probe key inválida.");
        }
    }

    private static void validate(ProbeRequest body) {
        if (body == null || body.payerUserId() == null || body.amountClp() <= 0
                || blank(body.providerToken()) || blank(body.payerEmail()) || blank(body.paymentMethodId())) {
            throw ApiProblem.badRequest("PAYMENT_SANDBOX_PROBE_INVALID", "Probe payload inválido.");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record ProbeRequest(UUID payerUserId, String providerToken, String payerEmail,
                               String paymentMethodId, long amountClp) {}
    public record FullCycleResult(UUID reservationId, String reservationStatus, UUID captureId,
                                  UUID refundId, long amountClp, String finalStatus) {}
    public record ReserveReleaseResult(UUID reservationId, String finalStatus, long amountClp) {}
}

package cl.tiempojusto.app.payment;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.provider.MercadoPagoTransport;
import cl.tiempojusto.finance.payment.provider.PaymentInstrumentResolver.OpaquePaymentInstrument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HTTPS transport for the Mercado Pago Payments API candidate.
 *
 * This class never logs or persists the provider token. It is not registered as
 * a Spring bean by default: production activation requires explicit credentials,
 * a tokenized instrument resolver and a commercial/legal provider decision.
 */
public final class MercadoPagoHttpTransport implements MercadoPagoTransport {
    private static final String IDEMPOTENCY = "X-Idempotency-Key";
    private final RestClient client;

    public MercadoPagoHttpTransport(RestClient.Builder builder, String baseUrl, String accessToken) {
        Objects.requireNonNull(builder, "builder");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl is required");
        if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("accessToken is required");
        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public AuthorizationResult authorize(OpaquePaymentInstrument instrument, long amountClp,
                                         String idempotencyKey, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_amount", amountClp);
        body.put("token", instrument.providerToken());
        body.put("capture", false);
        body.put("installments", 1);
        body.put("payment_method_id", instrument.paymentMethodId());
        body.put("payer", Map.of("email", instrument.payerEmail()));

        Map<?, ?> response = exchange(() -> client.post()
                .uri("/v1/payments")
                .header(IDEMPOTENCY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class));

        return new AuthorizationResult(
                requiredId(response, "id"), mapStatus(string(response, "status")),
                amount(response, "transaction_amount", amountClp), now);
    }

    @Override
    public CaptureResult capture(String providerPaymentId, long amountClp,
                                 String idempotencyKey, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("capture", true);
        body.put("transaction_amount", amountClp);

        Map<?, ?> response = exchange(() -> client.put()
                .uri("/v1/payments/{id}", providerPaymentId)
                .header(IDEMPOTENCY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class));

        return new CaptureResult(
                requiredId(response, "id"), mapStatus(string(response, "status")),
                amount(response, "transaction_amount", amountClp), now);
    }

    @Override
    public CancelResult cancel(String providerPaymentId, String idempotencyKey, Instant now) {
        Map<?, ?> response = exchange(() -> client.put()
                .uri("/v1/payments/{id}", providerPaymentId)
                .header(IDEMPOTENCY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "cancelled"))
                .retrieve()
                .body(Map.class));
        return new CancelResult(requiredId(response, "id"), mapStatus(string(response, "status")), now);
    }

    @Override
    public RefundResult refund(String providerPaymentId, long amountClp,
                               String idempotencyKey, Instant now) {
        Map<?, ?> response = exchange(() -> client.post()
                .uri("/v1/payments/{id}/refunds", providerPaymentId)
                .header(IDEMPOTENCY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amountClp))
                .retrieve()
                .body(Map.class));

        String refundId = requiredId(response, "id");
        long refunded = amount(response, "amount", amountClp);
        return new RefundResult(providerPaymentId, refundId, ProviderStatus.REFUNDED, refunded, now);
    }

    private static ProviderStatus mapStatus(String status) {
        if (status == null) return ProviderStatus.PENDING;
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "authorized" -> ProviderStatus.AUTHORIZED;
            case "approved" -> ProviderStatus.CAPTURED;
            case "cancelled", "canceled" -> ProviderStatus.CANCELLED;
            case "refunded", "charged_back" -> ProviderStatus.REFUNDED;
            case "rejected" -> ProviderStatus.REJECTED;
            default -> ProviderStatus.PENDING;
        };
    }

    private static Map<?, ?> exchange(RemoteCall call) {
        try {
            Map<?, ?> response = call.execute();
            if (response == null) throw error("PAYMENT_PROVIDER_RESPONSE_INVALID", "Mercado Pago returned an empty body");
            return response;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 400 || status == 402 || status == 422) {
                throw error("PAYMENT_DECLINED", "Mercado Pago rejected the payment operation");
            }
            if (status == 408 || status == 429) {
                throw error("PAYMENT_TIMEOUT", "Mercado Pago request timed out or was rate limited");
            }
            throw error("PAYMENT_PROVIDER_UNAVAILABLE", "Mercado Pago HTTP error " + status);
        } catch (ResourceAccessException ex) {
            throw error("PAYMENT_PROVIDER_UNAVAILABLE", "Mercado Pago could not be reached");
        }
    }

    private static String requiredId(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw error("PAYMENT_PROVIDER_RESPONSE_INVALID", "Mercado Pago response is missing " + key);
        }
        return value.toString();
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static long amount(Map<?, ?> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw error("PAYMENT_PROVIDER_RESPONSE_INVALID", "Mercado Pago returned a non-integer CLP amount");
        }
    }

    private static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    @FunctionalInterface
    private interface RemoteCall {
        Map<?, ?> execute();
    }
}
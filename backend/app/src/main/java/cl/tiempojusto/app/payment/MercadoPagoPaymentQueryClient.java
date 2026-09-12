package cl.tiempojusto.app.payment;

import cl.tiempojusto.finance.common.FinanceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Read-only provider client used exclusively for reconciliation after webhooks. */
public final class MercadoPagoPaymentQueryClient {
    private final RestClient client;

    public MercadoPagoPaymentQueryClient(RestClient.Builder builder, String baseUrl, String accessToken) {
        Objects.requireNonNull(builder, "builder");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl is required");
        if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("accessToken is required");
        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Snapshot getPayment(String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw error("PAYMENT_PROVIDER_ID_REQUIRED", "providerPaymentId is required");
        }
        try {
            Map<?, ?> response = client.get()
                    .uri("/v1/payments/{id}", providerPaymentId)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw error("PAYMENT_PROVIDER_RESPONSE_INVALID", "Mercado Pago returned an empty body");
            }
            String id = required(response, "id");
            String status = required(response, "status");
            long amountClp = amount(response, "transaction_amount", 0L);
            long refundedClp = amount(response, "transaction_amount_refunded", 0L);
            boolean captured = bool(response, "captured");
            return new Snapshot(id, status, amountClp, refundedClp, captured);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404) throw error("PAYMENT_PROVIDER_PAYMENT_NOT_FOUND", "Mercado Pago payment does not exist");
            if (status == 408 || status == 429) throw error("PAYMENT_TIMEOUT", "Mercado Pago lookup timed out or was rate limited");
            throw error("PAYMENT_PROVIDER_UNAVAILABLE", "Mercado Pago lookup HTTP error " + status);
        } catch (ResourceAccessException ex) {
            throw error("PAYMENT_PROVIDER_UNAVAILABLE", "Mercado Pago could not be reached");
        }
    }

    private static String required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw error("PAYMENT_PROVIDER_RESPONSE_INVALID", "Mercado Pago response is missing " + key);
        }
        return value.toString();
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

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b && b;
    }

    private static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    public record Snapshot(String providerPaymentId, String status, long amountClp,
                           long refundedClp, boolean captured) {}
}

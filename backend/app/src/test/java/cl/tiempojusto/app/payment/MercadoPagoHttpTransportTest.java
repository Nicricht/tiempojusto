package cl.tiempojusto.app.payment;

import cl.tiempojusto.finance.payment.provider.MercadoPagoTransport.ProviderStatus;
import cl.tiempojusto.finance.payment.provider.PaymentInstrumentResolver.OpaquePaymentInstrument;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoHttpTransportTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastIdempotency = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsManualAuthorizationWithoutLeakingTokenInReturnedObject() {
        var transport = new MercadoPagoHttpTransport(RestClient.builder(), baseUrl, "test-access");
        var instrument = new OpaquePaymentInstrument("MERCADO_PAGO", "opaque-provider-token",
                "payer@example.test", "master");

        var result = transport.authorize(instrument, 50_000, "idem-1", Instant.now());

        assertEquals(ProviderStatus.AUTHORIZED, result.status());
        assertEquals(50_000, result.authorizedAmountClp());
        assertEquals("idem-1", lastIdempotency.get());
        assertTrue(lastBody.get().contains("\"capture\":false"));
        assertTrue(lastBody.get().contains("opaque-provider-token"));
        assertFalse(result.toString().contains("opaque-provider-token"));
    }

    @Test
    void queryClientReadsNormalizedProviderSnapshot() {
        var query = new MercadoPagoPaymentQueryClient(RestClient.builder(), baseUrl, "test-access");
        var result = query.getPayment("900001");
        assertEquals("900001", result.providerPaymentId());
        assertEquals("approved", result.status());
        assertEquals(50_000, result.amountClp());
        assertEquals(10_000, result.refundedClp());
        assertTrue(result.captured());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        lastIdempotency.set(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        String response;
        if ("POST".equals(method) && "/v1/payments".equals(path)) {
            response = "{\"id\":\"900001\",\"status\":\"authorized\",\"transaction_amount\":50000}";
        } else if ("GET".equals(method) && path.endsWith("/900001")) {
            response = "{\"id\":\"900001\",\"status\":\"approved\",\"transaction_amount\":50000,\"transaction_amount_refunded\":10000,\"captured\":true}";
        } else if ("PUT".equals(method)) {
            response = "{\"id\":\"900001\",\"status\":\"approved\",\"transaction_amount\":50000}";
        } else if ("POST".equals(method) && path.endsWith("/refunds")) {
            response = "{\"id\":\"refund-1\",\"amount\":50000}";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

package cl.tiempojusto.app.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Low-cardinality operational metrics. Never tags with user ids, auction ids,
 * session ids, provider references or other request data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OperationalMetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;

    public OperationalMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String domain = domain(request.getRequestURI());
        Timer.Sample sample = Timer.start(registry);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            int status = response.getStatus();
            if (failed && status < 500) status = 500;
            String family = (status / 100) + "xx";
            String method = request.getMethod();

            Counter.builder("tiempojusto.domain.requests")
                    .description("HTTP requests grouped by bounded TiempoJusto operational domain")
                    .tag("domain", domain)
                    .tag("method", method)
                    .tag("status_family", family)
                    .register(registry)
                    .increment();

            sample.stop(Timer.builder("tiempojusto.domain.request.duration")
                    .description("HTTP request latency grouped by bounded TiempoJusto operational domain")
                    .tag("domain", domain)
                    .tag("method", method)
                    .tag("status_family", family)
                    .register(registry));
        }
    }

    static String domain(String path) {
        if (path.startsWith("/api/v1/webhooks/payments/")) return "payment_webhook";
        if (path.startsWith("/api/v1/webhooks/kyc/")) return "kyc_webhook";
        if (path.startsWith("/api/v1/auth/")) return "auth";
        if (path.startsWith("/api/v1/auctions/")) return "auction";
        if (path.startsWith("/api/v1/sessions/") || path.startsWith("/api/v1/appointments/")) return "session";
        if (path.contains("/payout") || path.startsWith("/api/v1/payments/")) return "payout";
        if (path.startsWith("/api/v1/admin/")) return "admin";
        if (path.startsWith("/api/v1/identity/")) return "identity";
        return "other";
    }
}

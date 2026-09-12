package cl.tiempojusto.app.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-instance defensive rate limiter implementing the technical defaults frozen in
 * api/openapi/README.md. Provider webhooks are excluded because they have signed,
 * idempotent reconciliation and provider retry semantics.
 *
 * For a future multi-replica production deployment this remains a last-line guard;
 * an edge/distributed limiter must enforce the same policies across replicas.
 */
@Component
@ConditionalOnProperty(name = "tiempojusto.security.rate-limit.enabled", havingValue = "true")
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final MeterRegistry registry;
    private final Clock clock;

    private final int readPerMinute;
    private final int writePerMinute;
    private final int bidPerMinute;
    private final int authPerMinuteIp;
    private final int safetyPerHour;
    private final int payoutPerHour;

    public ApiRateLimitFilter(
            MeterRegistry registry,
            @Value("${tiempojusto.security.rate-limit.read-per-minute:120}") int readPerMinute,
            @Value("${tiempojusto.security.rate-limit.write-per-minute:30}") int writePerMinute,
            @Value("${tiempojusto.security.rate-limit.bid-per-minute:30}") int bidPerMinute,
            @Value("${tiempojusto.security.rate-limit.auth-per-minute-ip:10}") int authPerMinuteIp,
            @Value("${tiempojusto.security.rate-limit.safety-per-hour:10}") int safetyPerHour,
            @Value("${tiempojusto.security.rate-limit.payout-per-hour:5}") int payoutPerHour) {
        this(registry, Clock.systemUTC(), readPerMinute, writePerMinute, bidPerMinute,
                authPerMinuteIp, safetyPerHour, payoutPerHour);
    }

    ApiRateLimitFilter(MeterRegistry registry,
                       Clock clock,
                       int readPerMinute,
                       int writePerMinute,
                       int bidPerMinute,
                       int authPerMinuteIp,
                       int safetyPerHour,
                       int payoutPerHour) {
        this.registry = registry;
        this.clock = clock;
        this.readPerMinute = positive(readPerMinute, "read-per-minute");
        this.writePerMinute = positive(writePerMinute, "write-per-minute");
        this.bidPerMinute = positive(bidPerMinute, "bid-per-minute");
        this.authPerMinuteIp = positive(authPerMinuteIp, "auth-per-minute-ip");
        this.safetyPerHour = positive(safetyPerHour, "safety-per-hour");
        this.payoutPerHour = positive(payoutPerHour, "payout-per-hour");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Policy policy = policyFor(request);
        if (policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.millis();
        String identity = policy.ipScoped() ? remoteAddress(request) : authenticatedIdentity(request);
        String key = policy.name() + ':' + identity + ':' + policy.scope();
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now));
        Decision decision = bucket.tryAcquire(now, policy.limit(), policy.windowMillis());

        if ((requests.incrementAndGet() & 1023L) == 0L) cleanup(now);

        if (!decision.allowed()) {
            registry.counter("tiempojusto.rate_limit.rejected", "policy", policy.name()).increment();
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setHeader("X-RateLimit-Policy", policy.name());
            response.getWriter().write("{\"category\":\"RATE_LIMIT\",\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Demasiadas solicitudes. Intenta nuevamente después del tiempo indicado.\",\"retryAfterSeconds\":"
                    + decision.retryAfterSeconds() + "}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Policy policyFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) return null;
        if (path.startsWith("/api/v1/webhooks/")) return null;
        if (path.startsWith("/api/v1/auth/")) {
            return new Policy("auth", authPerMinuteIp, 60_000L, "auth", true);
        }

        String method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (read) {
            return new Policy("read", readPerMinute, 60_000L, "api", false);
        }

        if (path.matches("/api/v1/auctions/[^/]+/bids/?")) {
            return new Policy("bid", bidPerMinute, 60_000L, path, false);
        }
        if (!path.startsWith("/api/v1/admin/") && (path.contains("/reports") || path.contains("/appeals"))) {
            return new Policy("safety", safetyPerHour, 3_600_000L, "safety", false);
        }
        if (!path.startsWith("/api/v1/admin/") && path.contains("/payout")) {
            return new Policy("payout", payoutPerHour, 3_600_000L, "payout", false);
        }
        return new Policy("write", writePerMinute, 60_000L, "api", false);
    }

    private static String authenticatedIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !authentication.getName().isBlank()
                && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        String devActor = request.getHeader("X-TJ-Actor-Id");
        if (devActor != null && !devActor.isBlank()) return "dev:" + devActor.trim();
        return "ip:" + remoteAddress(request);
    }

    private static String remoteAddress(HttpServletRequest request) {
        String value = request.getRemoteAddr();
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private void cleanup(long now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().stale(now));
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be > 0");
        return value;
    }

    private record Policy(String name, int limit, long windowMillis, String scope, boolean ipScoped) {}
    private record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final class Bucket {
        private long windowStartedAt;
        private long windowMillis;
        private int count;

        private Bucket(long now) {
            this.windowStartedAt = now;
        }

        private synchronized Decision tryAcquire(long now, int limit, long requestedWindowMillis) {
            if (windowMillis != requestedWindowMillis || now - windowStartedAt >= requestedWindowMillis) {
                windowStartedAt = now;
                windowMillis = requestedWindowMillis;
                count = 0;
            }
            if (count < limit) {
                count++;
                return new Decision(true, 0);
            }
            long remaining = Math.max(1L, requestedWindowMillis - (now - windowStartedAt));
            return new Decision(false, Math.max(1L, (remaining + 999L) / 1000L));
        }

        private synchronized boolean stale(long now) {
            long ttl = Math.max(windowMillis, 60_000L) * 2L;
            return now - windowStartedAt > ttl;
        }
    }
}

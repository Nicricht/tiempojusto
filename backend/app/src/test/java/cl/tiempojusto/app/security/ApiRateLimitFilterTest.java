package cl.tiempojusto.app.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiRateLimitFilterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authIsLimitedPerIp() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                new SimpleMeterRegistry(), clock,
                120, 30, 30, 10, 10, 5);

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = invoke(filter, "POST", "/api/v1/auth/refresh", "203.0.113.10");
            assertNotEquals(429, response.getStatus());
        }

        MockHttpServletResponse rejected = invoke(filter, "POST", "/api/v1/auth/refresh", "203.0.113.10");
        assertEquals(429, rejected.getStatus());
        assertEquals("auth", rejected.getHeader("X-RateLimit-Policy"));
        assertNotNull(rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void bidsAreScopedPerAuthenticatedUserAndAuction() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("oidc-subject-a", "n/a", List.of()));
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                new SimpleMeterRegistry(), clock,
                120, 30, 2, 10, 10, 5);

        assertNotEquals(429, invoke(filter, "POST", "/api/v1/auctions/auction-a/bids", "198.51.100.1").getStatus());
        assertNotEquals(429, invoke(filter, "POST", "/api/v1/auctions/auction-a/bids", "198.51.100.1").getStatus());
        assertEquals(429, invoke(filter, "POST", "/api/v1/auctions/auction-a/bids", "198.51.100.1").getStatus());

        // A different Auction has its own frozen 30/min/user/auction budget.
        assertNotEquals(429, invoke(filter, "POST", "/api/v1/auctions/auction-b/bids", "198.51.100.1").getStatus());
    }

    @Test
    void signedProviderWebhookSurfaceIsNotUserRateLimited() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                new SimpleMeterRegistry(), clock,
                1, 1, 1, 1, 1, 1);

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = invoke(
                    filter, "POST", "/api/v1/webhooks/payments/mercado-pago", "203.0.113.55");
            assertNotEquals(429, response.getStatus());
        }
    }

    private static MockHttpServletResponse invoke(ApiRateLimitFilter filter,
                                                  String method,
                                                  String path,
                                                  String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

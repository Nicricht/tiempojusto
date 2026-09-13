package cl.tiempojusto.app.ops;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesSafeCallerRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, "client-request_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("client-request_123", response.getHeader(RequestCorrelationFilter.HEADER));
    }

    @Test
    void replacesUnsafeOrOversizedRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(RequestCorrelationFilter.HEADER, "bad request id with spaces and\nnewline");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(RequestCorrelationFilter.HEADER);
        assertNotNull(generated);
        assertNotEquals("bad request id with spaces and\nnewline", generated);
        assertTrue(generated.length() <= 64);
    }
}

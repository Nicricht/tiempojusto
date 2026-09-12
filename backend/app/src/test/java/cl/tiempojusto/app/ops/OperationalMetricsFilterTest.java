package cl.tiempojusto.app.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationalMetricsFilterTest {
    @Test
    void classifiesOperationalDomainsWithoutResourceIds() {
        assertEquals("auth", OperationalMetricsFilter.domain("/api/v1/auth/me"));
        assertEquals("auction", OperationalMetricsFilter.domain("/api/v1/auctions/abc/bids"));
        assertEquals("session", OperationalMetricsFilter.domain("/api/v1/sessions/abc/online/join"));
        assertEquals("payment_webhook", OperationalMetricsFilter.domain("/api/v1/webhooks/payments/mercado-pago"));
        assertEquals("kyc_webhook", OperationalMetricsFilter.domain("/api/v1/webhooks/kyc/veriff"));
        assertEquals("payout", OperationalMetricsFilter.domain("/api/v1/payments/balance"));
        assertEquals("admin", OperationalMetricsFilter.domain("/api/v1/admin/audit-log"));
        assertEquals("identity", OperationalMetricsFilter.domain("/api/v1/identity/verifications"));
        assertEquals("other", OperationalMetricsFilter.domain("/api/v1/profiles/abc"));
    }
}

package cl.tiempojusto.app.config;

import cl.tiempojusto.finance.ledger.InMemoryLedger;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.finance.settlement.FinanceEngine;
import cl.tiempojusto.geo.GeoEligibilityService;
import cl.tiempojusto.geo.MockRoutingPort;
import cl.tiempojusto.geo.RoutingPort;
import cl.tiempojusto.media.MockWebRtcPort;
import cl.tiempojusto.media.WebRtcPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class CoreModulesConfiguration {

    @Bean
    public InMemoryLedger inMemoryLedger() {
        return new InMemoryLedger();
    }

    @Bean
    @Profile({"default", "dev", "test", "ci"})
    public MockPaymentPort mockPaymentPort() {
        return new MockPaymentPort();
    }

    @Bean
    public FinanceEngine financeEngine(PaymentPort paymentPort, InMemoryLedger ledger) {
        return new FinanceEngine(paymentPort, ledger);
    }

    @Bean
    @Profile({"default", "dev", "test", "ci"})
    public MockRoutingPort mockRoutingPort() {
        return new MockRoutingPort();
    }

    @Bean
    public GeoEligibilityService geoEligibilityService(RoutingPort routingPort) {
        return new GeoEligibilityService(routingPort);
    }

    @Bean
    @Profile({"default", "dev", "test", "ci"})
    public MockWebRtcPort mockWebRtcPort() {
        return new MockWebRtcPort();
    }

    @Bean
    public WebRtcPort webRtcPort(MockWebRtcPort mockWebRtcPort) {
        return mockWebRtcPort;
    }
}

package cl.tiempojusto.app.config;

import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import cl.tiempojusto.geo.MockRoutingPort;
import cl.tiempojusto.geo.RoutingPort;
import cl.tiempojusto.media.MockWebRtcPort;
import cl.tiempojusto.media.WebRtcPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CoreModulesConfigurationStagingWebRtcTest {

    @Test
    void stagingWithExplicitMockMediaProviderWiresWebRtcPort() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("staging");
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "tiempojusto.payment.provider=MOCK",
                    "tiempojusto.media.provider=MOCK");
            context.registerBean(RoutingPort.class, MockRoutingPort::new);
            context.register(CoreModulesConfiguration.class);
            context.refresh();

            assertInstanceOf(MockPaymentPort.class, context.getBean(PaymentPort.class));
            assertInstanceOf(MockWebRtcPort.class, context.getBean(WebRtcPort.class));
        }
    }
}

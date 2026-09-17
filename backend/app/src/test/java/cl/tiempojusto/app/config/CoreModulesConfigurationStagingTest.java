package cl.tiempojusto.app.config;

import cl.tiempojusto.finance.payment.MockPaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.geo.MockRoutingPort;
import cl.tiempojusto.geo.RoutingPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CoreModulesConfigurationStagingTest {

    @Test
    void stagingWithExplicitMockProviderWiresPaymentPort() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("staging");
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context, "tiempojusto.payment.provider=MOCK");
            context.registerBean(RoutingPort.class, MockRoutingPort::new);
            context.register(CoreModulesConfiguration.class);
            context.refresh();

            assertInstanceOf(MockPaymentPort.class, context.getBean(PaymentPort.class));
        }
    }
}

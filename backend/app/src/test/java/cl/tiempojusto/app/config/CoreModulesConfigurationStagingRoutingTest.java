package cl.tiempojusto.app.config;

import cl.tiempojusto.geo.MockRoutingPort;
import cl.tiempojusto.geo.RoutingPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CoreModulesConfigurationStagingRoutingTest {

    @Test
    void stagingWiresMockRoutingPort() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("staging");
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "tiempojusto.payment.provider=MOCK",
                    "tiempojusto.media.provider=MOCK");
            context.register(CoreModulesConfiguration.class);
            context.refresh();

            assertInstanceOf(MockRoutingPort.class, context.getBean(RoutingPort.class));
        }
    }
}

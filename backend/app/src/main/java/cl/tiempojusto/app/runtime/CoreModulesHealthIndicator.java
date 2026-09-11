package cl.tiempojusto.app.runtime;

import cl.tiempojusto.finance.settlement.FinanceEngine;
import cl.tiempojusto.geo.GeoEligibilityService;
import cl.tiempojusto.media.WebRtcPort;
import cl.tiempojusto.statemachine.online.OnlineSession;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("tiempoJustoCore")
public class CoreModulesHealthIndicator implements HealthIndicator {

    private final FinanceEngine financeEngine;
    private final GeoEligibilityService geoEligibilityService;
    private final WebRtcPort webRtcPort;

    public CoreModulesHealthIndicator(FinanceEngine financeEngine,
                                      GeoEligibilityService geoEligibilityService,
                                      WebRtcPort webRtcPort) {
        this.financeEngine = financeEngine;
        this.geoEligibilityService = geoEligibilityService;
        this.webRtcPort = webRtcPort;
    }

    @Override
    public Health health() {
        boolean stateMachinesLinked = OnlineSession.class.getName().startsWith("cl.tiempojusto.statemachine");
        boolean mediaHealthy = webRtcPort.healthy();
        if (!stateMachinesLinked || !mediaHealthy || financeEngine == null || geoEligibilityService == null) {
            return Health.down().build();
        }
        return Health.up().build();
    }
}

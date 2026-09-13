package cl.tiempojusto.app.runtime;

import cl.tiempojusto.app.application.PersistentSettlementApplicationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionSettlementJob {
    private static final Logger log = LoggerFactory.getLogger(SessionSettlementJob.class);
    private final PersistentSettlementApplicationService settlements;
    private final Counter posted;
    private final Counter zeroBilling;
    private final Counter roundingBlocked;
    private final Counter retry;

    public SessionSettlementJob(PersistentSettlementApplicationService settlements,
                                MeterRegistry registry) {
        this.settlements = settlements;
        this.posted = registry.counter("tiempojusto.settlement.attempts", "result", "posted");
        this.zeroBilling = registry.counter("tiempojusto.settlement.attempts", "result", "zero_billing");
        this.roundingBlocked = registry.counter("tiempojusto.settlement.attempts", "result", "pending_rounding_policy");
        this.retry = registry.counter("tiempojusto.settlement.attempts", "result", "retry");
    }

    @Scheduled(
            fixedDelayString = "${tiempojusto.finance.settlement-scan-ms:1000}",
            initialDelayString = "${tiempojusto.finance.settlement-initial-delay-ms:1000}")
    public void settleFinishedSessions() {
        for (UUID sessionId : settlements.unsettledFinishedSessions(50)) {
            try {
                var result = settlements.processSession(sessionId);
                switch (result.status()) {
                    case "POSTED" -> posted.increment();
                    case "ZERO_BILLING" -> zeroBilling.increment();
                    case "PENDING_ROUNDING_POLICY" -> roundingBlocked.increment();
                    default -> log.warn("Unexpected settlement status {} for session {}", result.status(), sessionId);
                }
            } catch (RuntimeException ex) {
                retry.increment();
                // Provider failures are retried by a later scan. Session ENDED state remains authoritative.
                log.warn("Session settlement retry pending for {}: {}", sessionId, ex.getMessage());
            }
        }
    }
}

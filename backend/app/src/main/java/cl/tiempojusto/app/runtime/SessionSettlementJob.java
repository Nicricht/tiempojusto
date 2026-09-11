package cl.tiempojusto.app.runtime;

import cl.tiempojusto.app.application.PersistentSettlementApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionSettlementJob {
    private static final Logger log = LoggerFactory.getLogger(SessionSettlementJob.class);
    private final PersistentSettlementApplicationService settlements;

    public SessionSettlementJob(PersistentSettlementApplicationService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(
            fixedDelayString = "${tiempojusto.finance.settlement-scan-ms:1000}",
            initialDelayString = "${tiempojusto.finance.settlement-initial-delay-ms:1000}")
    public void settleFinishedSessions() {
        for (UUID sessionId : settlements.unsettledFinishedSessions(50)) {
            try {
                settlements.processSession(sessionId);
            } catch (RuntimeException ex) {
                // Provider failures are retried by a later scan. Session ENDED state remains authoritative.
                log.warn("Session settlement retry pending for {}: {}", sessionId, ex.getMessage());
            }
        }
    }
}

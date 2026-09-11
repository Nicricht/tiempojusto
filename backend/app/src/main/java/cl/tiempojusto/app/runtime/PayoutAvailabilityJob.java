package cl.tiempojusto.app.runtime;

import cl.tiempojusto.app.application.PayoutAvailabilityApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PayoutAvailabilityJob {
    private static final Logger log = LoggerFactory.getLogger(PayoutAvailabilityJob.class);
    private final PayoutAvailabilityApplicationService payouts;

    public PayoutAvailabilityJob(PayoutAvailabilityApplicationService payouts) {
        this.payouts = payouts;
    }

    @Scheduled(
            fixedDelayString = "${tiempojusto.finance.payout-release-scan-ms:5000}",
            initialDelayString = "${tiempojusto.finance.payout-release-initial-delay-ms:2000}")
    public void releaseEligiblePayouts() {
        for (UUID payoutId : payouts.duePayouts(50)) {
            try {
                payouts.releaseIfEligible(payoutId);
            } catch (RuntimeException ex) {
                log.warn("Payout availability release retry pending for {}: {}", payoutId, ex.getMessage());
            }
        }
    }
}

package cl.tiempojusto.app.runtime;

import cl.tiempojusto.app.application.PayoutAvailabilityApplicationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PayoutAvailabilityJob {
    private static final Logger log = LoggerFactory.getLogger(PayoutAvailabilityJob.class);
    private final PayoutAvailabilityApplicationService payouts;
    private final Counter available;
    private final Counter skipped;
    private final Counter retry;

    public PayoutAvailabilityJob(PayoutAvailabilityApplicationService payouts,
                                 MeterRegistry registry) {
        this.payouts = payouts;
        this.available = registry.counter("tiempojusto.payout.release.attempts", "result", "available");
        this.skipped = registry.counter("tiempojusto.payout.release.attempts", "result", "skipped");
        this.retry = registry.counter("tiempojusto.payout.release.attempts", "result", "retry");
    }

    @Scheduled(
            fixedDelayString = "${tiempojusto.finance.payout-release-scan-ms:5000}",
            initialDelayString = "${tiempojusto.finance.payout-release-initial-delay-ms:2000}")
    public void releaseEligiblePayouts() {
        for (UUID payoutId : payouts.duePayouts(50)) {
            try {
                var result = payouts.releaseIfEligible(payoutId);
                if ("AVAILABLE".equals(result.status())) available.increment();
                else skipped.increment();
            } catch (RuntimeException ex) {
                retry.increment();
                log.warn("Payout availability release retry pending for {}: {}", payoutId, ex.getMessage());
            }
        }
    }
}

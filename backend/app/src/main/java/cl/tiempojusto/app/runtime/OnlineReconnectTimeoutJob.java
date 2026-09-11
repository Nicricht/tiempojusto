package cl.tiempojusto.app.runtime;

import cl.tiempojusto.app.application.OnlineReconnectApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tiempojusto.online.reconnect-watch-enabled", havingValue = "true", matchIfMissing = true)
public class OnlineReconnectTimeoutJob {
    private final OnlineReconnectApplicationService reconnect;

    public OnlineReconnectTimeoutJob(OnlineReconnectApplicationService reconnect) {
        this.reconnect = reconnect;
    }

    @Scheduled(fixedDelayString = "${tiempojusto.online.reconnect-watch-delay-ms:1000}")
    public void finishExpiredReconnects() {
        for (var sessionId : reconnect.dueReconnectSessions()) {
            try {
                reconnect.evaluate(sessionId);
            } catch (RuntimeException ignored) {
                // One bad session must not block the rest of the watcher batch.
            }
        }
    }
}

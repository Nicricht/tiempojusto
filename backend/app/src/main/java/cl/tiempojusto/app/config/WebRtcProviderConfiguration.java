package cl.tiempojusto.app.config;

import cl.tiempojusto.media.CoturnWebRtcPort;
import cl.tiempojusto.media.WebRtcPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Arrays;

@Configuration
public class WebRtcProviderConfiguration {
    @Bean
    @Primary
    @ConditionalOnProperty(name = "tiempojusto.media.provider", havingValue = "COTURN")
    public WebRtcPort coturnWebRtcPort(Environment env) {
        String urls = env.getRequiredProperty("tiempojusto.media.turn.urls");
        String key = env.getRequiredProperty("tiempojusto.media.turn.shared-secret");
        long ttl = env.getProperty("tiempojusto.media.turn.credential-ttl-seconds", Long.class, 600L);
        return new CoturnWebRtcPort(
                Arrays.stream(urls.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList(),
                key,
                Duration.ofSeconds(ttl));
    }
}

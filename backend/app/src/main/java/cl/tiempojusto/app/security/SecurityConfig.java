package cl.tiempojusto.app.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ApiRateLimitFilter> rateLimitFilter,
            @Value("${tiempojusto.auth.jwt-enabled:false}") boolean jwtEnabled,
            @Value("${TJ_ENVIRONMENT:dev}") String environment) throws Exception {

        // TiempoJusto uses a stateless Authorization: Bearer model. It does not use
        // cookie-backed server sessions for the API, so CSRF is disabled at this boundary.
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        rateLimitFilter.ifAvailable(filter -> http.addFilterBefore(filter, AuthorizationFilter.class));

        if (jwtEnabled) {
            boolean hardened = "staging".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
            http.authorizeHttpRequests(auth -> {
                        auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll();
                        if (hardened) {
                            // The payment sandbox probe has its own secret-key gate and is the
                            // only internal route allowed through a hardened runtime boundary.
                            auth.requestMatchers("/internal/payment-sandbox/**").permitAll();
                            auth.requestMatchers("/internal/**").denyAll();
                        } else {
                            // CI/dev-only golden-path controllers are profile restricted.
                            auth.requestMatchers("/internal/**").permitAll();
                        }
                        auth.requestMatchers("/api/v1/auth/refresh").permitAll();
                        // This controller only exists in dev/test/ci profiles.
                        auth.requestMatchers("/api/v1/sandbox/identity/**").permitAll();
                        // Provider callbacks authenticate cryptographically inside their adapters.
                        auth.requestMatchers("/api/v1/webhooks/kyc/**").permitAll();
                        auth.requestMatchers("/api/v1/webhooks/payments/mercado-pago").permitAll();
                        auth.requestMatchers("/api/v1/**").authenticated();
                        auth.anyRequest().permitAll();
                    })
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        } else {
            // Legacy/dev mode remains fail-closed at ActorContext. This filter does not
            // authenticate requests and must never be enabled in staging/production.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${tiempojusto.security.cors.allowed-origins:}") String rawAllowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(rawAllowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "If-Match", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "X-RateLimit-Policy"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

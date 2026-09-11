package cl.tiempojusto.app.security;

import cl.tiempojusto.app.api.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "tiempojusto.auth.jwt-enabled", havingValue = "false", matchIfMissing = true)
public class HeaderActorContext implements ActorContext {
    private static final String HEADER = "X-TJ-Actor-Id";
    private final boolean enabled;

    public HeaderActorContext(@Value("${tiempojusto.auth.dev-header-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public UUID requireActor(HttpServletRequest request) {
        if (!enabled) {
            throw ApiProblem.unavailable("AUTH_ADAPTER_NOT_CONFIGURED",
                    "OAuth2/JWT no está habilitado y el header de desarrollo está deshabilitado.");
        }
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            throw ApiProblem.unauthorized("ACTOR_REQUIRED", "Falta el actor autenticado.");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiProblem.unauthorized("ACTOR_INVALID", "El actor autenticado no es un UUID válido.");
        }
    }
}

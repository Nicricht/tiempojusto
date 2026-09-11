package cl.tiempojusto.app.security;

import cl.tiempojusto.app.api.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "tiempojusto.auth.jwt-enabled", havingValue = "true")
public class JwtActorContext implements ActorContext {
    private final JdbcTemplate jdbc;

    public JwtActorContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID requireActor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) || !authentication.isAuthenticated()) {
            throw ApiProblem.unauthorized("BEARER_TOKEN_REQUIRED", "Se requiere un access token OAuth2 válido.");
        }

        var jwt = jwtAuthentication.getToken();
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw ApiProblem.unauthorized("JWT_IDENTITY_INVALID", "El access token no contiene issuer/subject válidos.");
        }

        List<ActorRow> rows = jdbc.query("""
                select oi.user_id, u.account_status::text
                  from iam.oauth_identity oi
                  join iam.app_user u on u.id = oi.user_id
                 where oi.issuer = ?
                   and oi.subject = ?
                   and oi.revoked_at is null
                """, (rs, rowNum) -> new ActorRow(
                rs.getObject("user_id", UUID.class),
                rs.getString("account_status")), issuer, subject);

        if (rows.isEmpty()) {
            throw ApiProblem.unauthorized("OAUTH_IDENTITY_NOT_LINKED",
                    "La identidad OAuth2 autenticada no está vinculada a una cuenta de TiempoJusto.");
        }

        ActorRow actor = rows.getFirst();
        if (!"ACTIVE".equals(actor.accountStatus())) {
            throw ApiProblem.forbidden("ACCOUNT_NOT_ACTIVE",
                    "La cuenta autenticada no está habilitada para operaciones de negocio.");
        }
        return actor.userId();
    }

    private record ActorRow(UUID userId, String accountStatus) {}
}

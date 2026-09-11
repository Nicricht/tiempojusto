package cl.tiempojusto.app.api;

import cl.tiempojusto.app.security.ActorContext;
import cl.tiempojusto.app.security.OAuthRefreshService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final ActorContext actors;
    private final JdbcTemplate jdbc;
    private final OAuthRefreshService refreshService;

    public AuthController(ActorContext actors, JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          OAuthRefreshService refreshService) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.refreshService = refreshService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        UUID actorId = actors.requireActor(request);
        return jdbc.queryForObject("""
                select id, public_id, role::text, account_status::text
                  from iam.app_user
                 where id = ?
                """, (rs, rowNum) -> Map.of(
                "userId", rs.getObject("id", UUID.class),
                "publicId", rs.getString("public_id"),
                "role", rs.getString("role"),
                "accountStatus", rs.getString("account_status")), actorId);
    }

    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> refresh(@RequestBody RefreshRequest request) {
        if (refreshService == null) {
            throw ApiProblem.unavailable("REFRESH_ADAPTER_NOT_CONFIGURED",
                    "El intercambio de refresh token no está configurado.");
        }
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw ApiProblem.badRequest("REFRESH_TOKEN_REQUIRED", "refreshToken es obligatorio.");
        }

        OAuthRefreshService.ProviderResponse provider = refreshService.refresh(request.refreshToken());
        return ResponseEntity.status(HttpStatusCode.valueOf(provider.statusCode()))
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(provider.body() == null ? "{}" : provider.body());
    }

    public record RefreshRequest(String refreshToken) {}
}

package cl.tiempojusto.app.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/ci/oauth")
@Profile("ci")
public class CiOAuthProviderController {

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestBody MultiValueMap<String, String> form) {
        String grantType = form.getFirst("grant_type");
        String refreshToken = form.getFirst("refresh_token");
        if (!"refresh_token".equals(grantType) || !"ci-refresh-token".equals(refreshToken)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_grant",
                    "error_description", "CI refresh token rejected"));
        }
        return ResponseEntity.ok(Map.of(
                "access_token", "ci-rotated-access-token",
                "refresh_token", "ci-refresh-token-rotated",
                "token_type", "Bearer",
                "expires_in", 900));
    }
}

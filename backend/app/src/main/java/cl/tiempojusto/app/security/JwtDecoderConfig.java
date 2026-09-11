package cl.tiempojusto.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
@ConditionalOnProperty(name = "tiempojusto.auth.jwt-enabled", havingValue = "true")
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(
            Environment environment,
            @Value("${tiempojusto.auth.jwt.issuer:}") String issuer,
            @Value("${tiempojusto.auth.jwt.audience:}") String audience,
            @Value("${tiempojusto.auth.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${tiempojusto.auth.jwt.hmac-secret-b64:}") String hmacSecretB64) {

        if (issuer.isBlank()) {
            throw new IllegalStateException("TJ_AUTH_JWT_ISSUER is required when JWT auth is enabled");
        }
        if (audience.isBlank()) {
            throw new IllegalStateException("TJ_AUTH_JWT_AUDIENCE is required when JWT auth is enabled");
        }

        NimbusJwtDecoder decoder;
        if (!hmacSecretB64.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("ci", "dev", "test"))) {
                throw new IllegalStateException("HMAC JWT keys are restricted to ci/dev/test; production must use JWKS");
            }
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(hmacSecretB64);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("TJ_AUTH_JWT_HMAC_SECRET_B64 must be valid base64", ex);
            }
            if (secret.length < 32) {
                throw new IllegalStateException("HS256 CI/dev secret must contain at least 32 bytes");
            }
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        } else {
            if (jwkSetUri.isBlank()) {
                throw new IllegalStateException("TJ_AUTH_JWT_JWK_SET_URI is required for production JWT validation");
            }
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }

        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Required audience is missing", null));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }
}

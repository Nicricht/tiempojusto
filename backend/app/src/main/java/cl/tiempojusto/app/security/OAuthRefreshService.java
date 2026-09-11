package cl.tiempojusto.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
@ConditionalOnProperty(name = "tiempojusto.auth.refresh.enabled", havingValue = "true")
public class OAuthRefreshService {
    private final RestClient restClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    public OAuthRefreshService(
            RestClient.Builder restClientBuilder,
            @Value("${tiempojusto.auth.refresh.token-uri:}") String tokenUri,
            @Value("${tiempojusto.auth.refresh.client-id:}") String clientId,
            @Value("${tiempojusto.auth.refresh.client-secret:}") String clientSecret) {
        if (tokenUri.isBlank()) {
            throw new IllegalStateException("TJ_AUTH_REFRESH_TOKEN_URI is required when refresh proxy is enabled");
        }
        if (clientId.isBlank()) {
            throw new IllegalStateException("TJ_AUTH_REFRESH_CLIENT_ID is required when refresh proxy is enabled");
        }
        this.restClient = restClientBuilder.build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public ProviderResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken is required");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);

        var request = restClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON);

        if (clientSecret == null || clientSecret.isBlank()) {
            form.add("client_id", clientId);
        } else {
            request.headers(headers -> headers.setBasicAuth(clientId, clientSecret));
        }

        try {
            var response = request.body(form).retrieve().toEntity(String.class);
            return new ProviderResponse(response.getStatusCode().value(), response.getBody());
        } catch (RestClientResponseException ex) {
            return new ProviderResponse(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        }
    }

    public record ProviderResponse(int statusCode, String body) {}
}

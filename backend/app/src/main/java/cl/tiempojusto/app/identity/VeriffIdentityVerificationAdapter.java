package cl.tiempojusto.app.identity;

import cl.tiempojusto.identity.IdentityProviderCapabilities;
import cl.tiempojusto.identity.IdentityVerificationPort;
import cl.tiempojusto.identity.VeriffDecisionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Candidate Veriff adapter. It is disabled unless TJ_KYC_PROVIDER=veriff.
 *
 * TiempoJusto does not upload or persist provider documents/biometrics through
 * this adapter. The user completes the provider-hosted verification session and
 * only the opaque session reference plus normalized decision enter our system.
 *
 * Both incoming webhooks and Veriff API response bodies are authenticated with
 * the shared-secret HMAC before their contents are trusted.
 */
@Component
@ConditionalOnProperty(prefix = "tiempojusto.kyc", name = "provider", havingValue = "veriff")
public final class VeriffIdentityVerificationAdapter implements IdentityVerificationPort {
    private static final Pattern VERIFICATION_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{36})\\\"");
    private final RestClient client;
    private final ObjectMapper json;
    private final String apiKey;
    private final byte[] sharedSecret;

    public VeriffIdentityVerificationAdapter(
            ObjectMapper json,
            @Value("${tiempojusto.kyc.veriff.base-url:}") String baseUrl,
            @Value("${tiempojusto.kyc.veriff.api-key:}") String apiKey,
            @Value("${tiempojusto.kyc.veriff.shared-secret:}") String sharedSecret) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("Veriff base URL is required");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Veriff API key is required");
        if (sharedSecret == null || sharedSecret.isBlank()) throw new IllegalStateException("Veriff shared secret is required");
        this.json = json;
        this.apiKey = apiKey;
        this.sharedSecret = sharedSecret.getBytes(StandardCharsets.UTF_8);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-AUTH-CLIENT", apiKey)
                .build();
    }

    @Override
    public String providerCode() {
        return "VERIFF";
    }

    @Override
    public IdentityProviderCapabilities capabilities() {
        return new IdentityProviderCapabilities(
                true,
                true,
                true,
                false,
                true,
                true);
    }

    @Override
    public VerificationSession start(StartVerification command) {
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("callback", command.callbackUrl());
        // endUserId is already a non-semantic UUID and is enough to correlate the
        // provider session. Avoid duplicating the same identifier in vendorData.
        verification.put("endUserId", command.userId().toString());

        Map<?, ?> response = exchange(() -> client.post()
                .uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("verification", verification))
                .retrieve()
                .toEntity(String.class));

        Map<?, ?> value = nested(response, "verification");
        String id = requiredString(value, "id");
        String url = requiredString(value, "url");
        return new VerificationSession(id, url, command.now());
    }

    @Override
    public Optional<VerificationDecision> pollDecision(String providerReference, Instant now) {
        if (providerReference == null || providerReference.isBlank()) return Optional.empty();
        String signature = hmacHex(providerReference);

        Map<?, ?> response = exchange(() -> client.get()
                .uri("/v1/sessions/{id}/decision", providerReference)
                .header("X-HMAC-SIGNATURE", signature)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .toEntity(String.class));

        Object verificationValue = response.get("verification");
        if (!(verificationValue instanceof Map<?, ?> verification) || verification.isEmpty()) {
            return Optional.empty();
        }

        String status = string(verification, "status");
        Integer code = integer(verification.get("code"));
        String reasonCode = string(verification, "reasonCode");
        LocalDate dob = null;
        Object personValue = verification.get("person");
        if (personValue instanceof Map<?, ?> person) {
            dob = date(string(person, "dateOfBirth"));
        }
        String country = null;
        Object documentValue = verification.get("document");
        if (documentValue instanceof Map<?, ?> document) {
            country = string(document, "country");
        }

        return Optional.of(VeriffDecisionMapper.map(
                providerReference, status, code, dob, country, reasonCode, now));
    }

    @Override
    public boolean verifyWebhook(String rawBody, Map<String, String> headers) {
        if (rawBody == null || headers == null) return false;
        String client = headers.get("x-auth-client");
        String signature = headers.get("x-hmac-signature");
        return apiKeyMatches(client) && hmacMatches(rawBody, signature);
    }

    @Override
    public Optional<String> providerReferenceFromWebhook(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return Optional.empty();
        int marker = rawBody.indexOf("\"verification\"");
        if (marker < 0) return Optional.empty();
        Matcher matcher = VERIFICATION_ID.matcher(rawBody.substring(marker));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Map<?, ?> exchange(RemoteCall call) {
        try {
            ResponseEntity<String> response = call.execute();
            String rawBody = response.getBody();
            if (rawBody == null || rawBody.isBlank()) {
                throw new IdentityProviderException("KYC_PROVIDER_RESPONSE_INVALID", "Veriff returned an empty response body");
            }
            String responseClient = response.getHeaders().getFirst("X-AUTH-CLIENT");
            String responseSignature = response.getHeaders().getFirst("X-HMAC-SIGNATURE");
            if (!apiKeyMatches(responseClient) || !hmacMatches(rawBody, responseSignature)) {
                throw new IdentityProviderException("KYC_PROVIDER_RESPONSE_SIGNATURE_INVALID",
                        "Veriff response signature validation failed");
            }
            try {
                Object parsed = json.readValue(rawBody, Map.class);
                if (!(parsed instanceof Map<?, ?> map)) {
                    throw new IdentityProviderException("KYC_PROVIDER_RESPONSE_INVALID", "Veriff returned invalid JSON");
                }
                return map;
            } catch (IdentityProviderException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IdentityProviderException("KYC_PROVIDER_RESPONSE_INVALID", "Veriff returned invalid JSON");
            }
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 408 || status == 429) {
                throw new IdentityProviderException("KYC_PROVIDER_TIMEOUT", "Veriff timed out or rate limited the request");
            }
            if (status >= 400 && status < 500) {
                throw new IdentityProviderException("KYC_PROVIDER_REQUEST_REJECTED", "Veriff rejected the request");
            }
            throw new IdentityProviderException("KYC_PROVIDER_UNAVAILABLE", "Veriff HTTP error " + status);
        } catch (ResourceAccessException ex) {
            throw new IdentityProviderException("KYC_PROVIDER_UNAVAILABLE", "Veriff could not be reached");
        }
    }

    private boolean apiKeyMatches(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hmacMatches(String value, String signature) {
        if (signature == null || signature.isBlank()) return false;
        byte[] expected;
        byte[] actual;
        try {
            expected = HexFormat.of().parseHex(hmacHex(value));
            actual = HexFormat.of().parseHex(signature.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate Veriff HMAC", ex);
        }
    }

    private static Map<?, ?> nested(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> nested)) {
            throw new IdentityProviderException("KYC_PROVIDER_RESPONSE_INVALID", "Missing " + key);
        }
        return nested;
    }

    private static String requiredString(Map<?, ?> map, String key) {
        String value = string(map, key);
        if (value == null || value.isBlank()) {
            throw new IdentityProviderException("KYC_PROVIDER_RESPONSE_INVALID", "Missing " + key);
        }
        return value;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return null;
        try { return Integer.valueOf(value.toString()); } catch (NumberFormatException ex) { return null; }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException ex) { return null; }
    }

    @FunctionalInterface
    private interface RemoteCall {
        ResponseEntity<String> execute();
    }

    public static final class IdentityProviderException extends RuntimeException {
        private final String code;

        public IdentityProviderException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}

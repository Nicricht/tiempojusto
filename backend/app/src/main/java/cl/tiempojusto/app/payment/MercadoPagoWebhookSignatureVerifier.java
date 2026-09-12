package cl.tiempojusto.app.payment;

import cl.tiempojusto.app.api.ApiProblem;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies Mercado Pago x-signature using the documented HMAC-SHA256 manifest:
 * id:<data.id>;request-id:<x-request-id>;ts:<ts>;
 *
 * Missing manifest pairs are omitted, matching the provider SDK behavior.
 * The data.id value is normalized to lowercase as required by Mercado Pago for
 * alphanumeric resource ids.
 */
public final class MercadoPagoWebhookSignatureVerifier {
    private final byte[] secret;

    public MercadoPagoWebhookSignatureVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Mercado Pago webhook secret is required");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public VerifiedSignature verify(String xSignature, String xRequestId, String dataId) {
        if (xSignature == null || xSignature.isBlank()) {
            throw ApiProblem.unauthorized("PAYMENT_WEBHOOK_SIGNATURE_REQUIRED", "x-signature requerido.");
        }

        String ts = null;
        String v1 = null;
        for (String part : xSignature.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            if ("ts".equals(pair[0])) ts = pair[1].trim();
            if ("v1".equals(pair[0])) v1 = pair[1].trim().toLowerCase(Locale.ROOT);
        }
        if (ts == null || ts.isBlank() || v1 == null || v1.isBlank()) {
            throw ApiProblem.unauthorized("PAYMENT_WEBHOOK_SIGNATURE_INVALID", "x-signature incompleto.");
        }

        String normalizedDataId = normalize(dataId);
        StringBuilder manifest = new StringBuilder();
        if (normalizedDataId != null) manifest.append("id:").append(normalizedDataId).append(';');
        if (xRequestId != null && !xRequestId.isBlank()) manifest.append("request-id:").append(xRequestId).append(';');
        manifest.append("ts:").append(ts).append(';');

        byte[] expected = hmac(manifest.toString());
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(v1);
        } catch (IllegalArgumentException ex) {
            throw ApiProblem.unauthorized("PAYMENT_WEBHOOK_SIGNATURE_INVALID", "Firma webhook inválida.");
        }

        if (!MessageDigest.isEqual(expected, supplied)) {
            throw ApiProblem.unauthorized("PAYMENT_WEBHOOK_SIGNATURE_INVALID", "Firma webhook inválida.");
        }
        return new VerifiedSignature(ts, normalizedDataId, manifest.toString());
    }

    private byte[] hmac(String manifest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record VerifiedSignature(String timestamp, String normalizedDataId, String manifest) {}
}

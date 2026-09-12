package cl.tiempojusto.app.payment;

import cl.tiempojusto.app.api.ApiProblem;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MercadoPagoWebhookSignatureVerifierTest {
    private static final String SECRET = "sandbox-secret-for-tests";

    @Test
    void verifiesDocumentedManifestAndNormalizesDataId() throws Exception {
        String requestId = "2066ca19-c6f1-498a-be75-1923005edd06";
        String dataId = "ORD01JQ4S4KY8HWQ6NA5PXB65B3D3";
        String ts = "1742505638683";
        String normalized = dataId.toLowerCase();
        String manifest = "id:" + normalized + ";request-id:" + requestId + ";ts:" + ts + ";";
        String signature = "ts=" + ts + ",v1=" + hmac(manifest);

        var verified = new MercadoPagoWebhookSignatureVerifier(SECRET)
                .verify(signature, requestId, dataId);

        assertEquals(ts, verified.timestamp());
        assertEquals(normalized, verified.normalizedDataId());
        assertEquals(manifest, verified.manifest());
    }

    @Test
    void rejectsTamperedSignature() {
        var verifier = new MercadoPagoWebhookSignatureVerifier(SECRET);
        ApiProblem problem = assertThrows(ApiProblem.class,
                () -> verifier.verify("ts=1742505638683,v1=00", "request-1", "123"));
        assertEquals("PAYMENT_WEBHOOK_SIGNATURE_INVALID", problem.code());
    }

    @Test
    void omitsMissingRequestIdLikeProviderSdk() throws Exception {
        String ts = "1704908010";
        String manifest = "id:999999999;ts:" + ts + ";";
        String signature = "ts=" + ts + ",v1=" + hmac(manifest);

        var verified = new MercadoPagoWebhookSignatureVerifier(SECRET)
                .verify(signature, null, "999999999");
        assertEquals(manifest, verified.manifest());
    }

    private static String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}

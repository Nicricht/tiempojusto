package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.KycApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/kyc")
public class KycWebhookController {
    private final KycApplicationService kyc;

    public KycWebhookController(KycApplicationService kyc) {
        this.kyc = kyc;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<KycApplicationService.WebhookResult> receive(
            @PathVariable String provider,
            @RequestBody String rawBody,
            HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name ->
                headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        return ResponseEntity.ok(kyc.handleWebhook(provider, rawBody, headers));
    }
}

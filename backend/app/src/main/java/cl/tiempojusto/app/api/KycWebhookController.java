package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.KycWebhookReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
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
    private final KycWebhookReconciliationService webhooks;

    public KycWebhookController(KycWebhookReconciliationService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<KycWebhookReconciliationService.WebhookResult> receive(
            @PathVariable String provider,
            @RequestBody String rawBody,
            HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name ->
                headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(webhooks.accept(provider, rawBody, headers));
    }
}

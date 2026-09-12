package cl.tiempojusto.app.api;

import cl.tiempojusto.app.payment.MercadoPagoWebhookReconciliationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/payments/mercado-pago")
@ConditionalOnProperty(name = "tiempojusto.payment.provider", havingValue = "MERCADO_PAGO")
public class MercadoPagoWebhookController {
    private final MercadoPagoWebhookReconciliationService webhooks;

    public MercadoPagoWebhookController(MercadoPagoWebhookReconciliationService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MercadoPagoWebhookReconciliationService.Acceptance> receive(
            @RequestHeader(value = "x-signature", required = false) String signature,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestParam(value = "data.id", required = false) String dataId,
            @RequestParam(value = "type", required = false) String type,
            @RequestBody String rawBody) {
        var result = webhooks.accept(signature, requestId, dataId, type, rawBody);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }
}

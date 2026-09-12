package cl.tiempojusto.app.api;

import cl.tiempojusto.app.payment.EphemeralMercadoPagoInstrumentResolver;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/sandbox/payments/mercado-pago")
@ConditionalOnProperty(name = "tiempojusto.payment.provider", havingValue = "MERCADO_PAGO")
public class PaymentSandboxInstrumentController {
    private final ActorContext actors;
    private final EphemeralMercadoPagoInstrumentResolver resolver;

    public PaymentSandboxInstrumentController(ActorContext actors,
                                              EphemeralMercadoPagoInstrumentResolver resolver) {
        this.actors = actors;
        this.resolver = resolver;
    }

    @PostMapping("/instrument")
    public ResponseEntity<RegisteredInstrument> register(
            HttpServletRequest http,
            @RequestBody InstrumentRequest body) {
        if (body == null || blank(body.providerToken()) || blank(body.payerEmail()) || blank(body.paymentMethodId())) {
            throw ApiProblem.badRequest("PAYMENT_INSTRUMENT_INVALID",
                    "providerToken, payerEmail y paymentMethodId son obligatorios.");
        }
        var registered = resolver.register(
                actors.requireActor(http), body.providerToken(), body.payerEmail(), body.paymentMethodId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RegisteredInstrument("MERCADO_PAGO", registered.expiresAt(), true));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record InstrumentRequest(String providerToken, String payerEmail, String paymentMethodId) {}
    public record RegisteredInstrument(String provider, Instant expiresAt, boolean oneTime) {}
}

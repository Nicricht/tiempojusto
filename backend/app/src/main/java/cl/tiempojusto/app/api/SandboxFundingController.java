package cl.tiempojusto.app.api;

import cl.tiempojusto.app.security.ActorContext;
import cl.tiempojusto.finance.payment.MockPaymentPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sandbox/funding")
@Profile({"dev", "test", "ci"})
public class SandboxFundingController {
    private final MockPaymentPort payments;
    private final ActorContext actors;

    public SandboxFundingController(MockPaymentPort payments, ActorContext actors) {
        this.payments = payments;
        this.actors = actors;
    }

    @PostMapping("/me")
    public ResponseEntity<Map<String, Object>> fund(HttpServletRequest http, @RequestBody FundingRequest request) {
        UUID actor = actors.requireActor(http);
        if (request.amountClp() < 0) throw ApiProblem.badRequest("FUNDING_AMOUNT_INVALID", "amountClp debe ser >= 0.");
        payments.setAvailableFunds(actor, request.amountClp());
        return ResponseEntity.ok(Map.of("userId", actor, "availableFundsClp", payments.availableFunds(actor)));
    }

    public record FundingRequest(long amountClp) {}
}

package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.KycApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity/verifications")
public class KycController {
    private final KycApplicationService kyc;
    private final ActorContext actorContext;

    public KycController(KycApplicationService kyc, ActorContext actorContext) {
        this.kyc = kyc;
        this.actorContext = actorContext;
    }

    @PostMapping
    public ResponseEntity<KycApplicationService.StartResult> start(HttpServletRequest request) {
        return ResponseEntity.ok(kyc.start(actorContext.requireActor(request)));
    }

    @GetMapping("/latest")
    public ResponseEntity<KycApplicationService.StatusResult> latest(HttpServletRequest request) {
        return kyc.latest(actorContext.requireActor(request))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

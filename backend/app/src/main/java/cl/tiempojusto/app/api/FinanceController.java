package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.FinanceReadService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FinanceController {
    private final FinanceReadService finance;
    private final ActorContext actors;

    public FinanceController(FinanceReadService finance, ActorContext actors) {
        this.finance = finance;
        this.actors = actors;
    }

    @GetMapping("/payments/balance")
    public ResponseEntity<FinanceReadService.BalanceView> balance(HttpServletRequest request) {
        return ResponseEntity.ok(finance.balance(actors.requireActor(request)));
    }
}

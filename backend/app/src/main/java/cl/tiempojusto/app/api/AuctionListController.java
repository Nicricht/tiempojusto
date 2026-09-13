package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.AuctionDiscoveryApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auctions")
public class AuctionListController {
    private final AuctionDiscoveryApplicationService service;
    private final ActorContext actors;

    public AuctionListController(AuctionDiscoveryApplicationService service, ActorContext actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public ResponseEntity<AuctionDiscoveryApplicationService.Page> list(HttpServletRequest request,
                                                                         @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(service.listOpen(actors.requireActor(request), limit));
    }
}

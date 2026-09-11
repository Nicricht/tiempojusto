package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.AuctionApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sandbox/auctions")
@Profile({"dev", "test", "ci"})
public class SandboxAuctionController {
    private final AuctionApplicationService auctions;
    private final ActorContext actors;

    public SandboxAuctionController(AuctionApplicationService auctions, ActorContext actors) {
        this.auctions = auctions;
        this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<AuctionApplicationService.AuctionView> open(
            HttpServletRequest http,
            @RequestBody AuctionApplicationService.OpenRequest request) {
        return ResponseEntity.ok(auctions.openSandbox(actors.requireActor(http), request));
    }
}

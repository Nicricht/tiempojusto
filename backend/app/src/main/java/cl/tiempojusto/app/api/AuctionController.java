package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.AuctionApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auctions")
public class AuctionController {
    private final AuctionApplicationService auctions;
    private final ActorContext actors;

    public AuctionController(AuctionApplicationService auctions, ActorContext actors) {
        this.auctions = auctions;
        this.actors = actors;
    }

    @GetMapping("/{auctionId}")
    public ResponseEntity<AuctionApplicationService.AuctionView> get(@PathVariable UUID auctionId) {
        return ResponseEntity.ok(auctions.get(auctionId));
    }

    @PostMapping("/{auctionId}/close-now")
    public ResponseEntity<AuctionApplicationService.CloseNowResult> closeNow(
            HttpServletRequest http,
            @PathVariable UUID auctionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.ok(auctions.closeNow(actors.requireActor(http), auctionId, idempotencyKey));
    }
}

package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.ProposalApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ProposalController {
    private final ProposalApplicationService proposals;
    private final ActorContext actors;

    public ProposalController(ProposalApplicationService proposals, ActorContext actors) {
        this.proposals = proposals;
        this.actors = actors;
    }

    @PostMapping("/profiles/{profileId}/proposals")
    public ResponseEntity<ProposalApplicationService.ProposalView> create(
            HttpServletRequest http,
            @PathVariable UUID profileId,
            @RequestBody ProposalApplicationService.CreateRequest request) {
        UUID actor = actors.requireActor(http);
        var result = proposals.create(actor, profileId, request);
        return ResponseEntity.created(URI.create("/api/v1/proposals/" + result.id())).body(result);
    }

    @GetMapping("/proposals/{proposalId}")
    public ResponseEntity<ProposalApplicationService.ProposalView> get(
            HttpServletRequest http,
            @PathVariable UUID proposalId) {
        UUID actor = actors.requireActor(http);
        return ResponseEntity.ok(proposals.getForActor(actor, proposalId));
    }
}

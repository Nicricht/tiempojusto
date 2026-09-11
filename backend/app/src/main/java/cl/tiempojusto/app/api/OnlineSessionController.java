package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.OnlineApplicationService;
import cl.tiempojusto.app.application.OnlineSessionTerminationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OnlineSessionController {
    private final OnlineApplicationService online;
    private final OnlineSessionTerminationService termination;
    private final ActorContext actors;

    public OnlineSessionController(OnlineApplicationService online,
                                   OnlineSessionTerminationService termination,
                                   ActorContext actors) {
        this.online = online;
        this.termination = termination;
        this.actors = actors;
    }

    @PostMapping("/appointments/{appointmentId}/confirm-now")
    public ResponseEntity<OnlineApplicationService.ConfirmResult> confirmWinner(
            HttpServletRequest http,
            @PathVariable UUID appointmentId) {
        return ResponseEntity.ok(online.confirmWinner(actors.requireActor(http), appointmentId));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<OnlineApplicationService.SessionView> get(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(online.get(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/online/join")
    public ResponseEntity<OnlineApplicationService.JoinResult> join(
            HttpServletRequest http,
            @PathVariable UUID sessionId,
            @RequestBody OnlineApplicationService.JoinRequest request) {
        return ResponseEntity.ok(online.join(actors.requireActor(http), sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/paid-consent")
    public ResponseEntity<OnlineApplicationService.SessionView> paidConsent(
            HttpServletRequest http,
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok(online.acceptPaid(actors.requireActor(http), sessionId));
    }

    @PostMapping("/sessions/{sessionId}/finish")
    public ResponseEntity<OnlineSessionTerminationService.FinishResult> finish(
            HttpServletRequest http,
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok(termination.finish(actors.requireActor(http), sessionId));
    }
}

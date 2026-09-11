package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.OnlineReconnectApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/online")
public class OnlineReconnectController {
    private final OnlineReconnectApplicationService reconnect;
    private final ActorContext actors;

    public OnlineReconnectController(OnlineReconnectApplicationService reconnect, ActorContext actors) {
        this.reconnect = reconnect;
        this.actors = actors;
    }

    @PostMapping("/media-signal")
    public ResponseEntity<OnlineReconnectApplicationService.StateView> mediaSignal(
            HttpServletRequest http,
            @PathVariable UUID sessionId,
            @RequestBody OnlineReconnectApplicationService.MediaSignalRequest request) {
        return ResponseEntity.ok(reconnect.signal(actors.requireActor(http), sessionId, request));
    }

    @PostMapping("/resume-consent")
    public ResponseEntity<OnlineReconnectApplicationService.StateView> resumeConsent(
            HttpServletRequest http,
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok(reconnect.acceptResume(actors.requireActor(http), sessionId));
    }

    @GetMapping("/reconnect-state")
    public ResponseEntity<OnlineReconnectApplicationService.StateView> state(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(reconnect.get(sessionId));
    }
}

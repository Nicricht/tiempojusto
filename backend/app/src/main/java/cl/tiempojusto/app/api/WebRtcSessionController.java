package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.WebRtcSessionService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/online/webrtc")
public class WebRtcSessionController {
    private final WebRtcSessionService webRtc;
    private final ActorContext actors;

    public WebRtcSessionController(WebRtcSessionService webRtc, ActorContext actors) {
        this.webRtc = webRtc;
        this.actors = actors;
    }

    @GetMapping("/config")
    public ResponseEntity<WebRtcSessionService.WebRtcConfig> config(
            HttpServletRequest http,
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(webRtc.config(actors.requireActor(http), sessionId));
    }

    @PostMapping("/signals")
    public ResponseEntity<WebRtcSessionService.SignalAck> signal(
            HttpServletRequest http,
            @PathVariable UUID sessionId,
            @RequestBody WebRtcSessionService.SignalRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(webRtc.send(actors.requireActor(http), sessionId, request));
    }

    @GetMapping("/signals")
    public ResponseEntity<WebRtcSessionService.SignalBatch> poll(
            HttpServletRequest http,
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") long after) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(webRtc.poll(actors.requireActor(http), sessionId, after));
    }
}

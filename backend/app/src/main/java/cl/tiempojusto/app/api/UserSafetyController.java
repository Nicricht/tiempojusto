package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.UserSafetyApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class UserSafetyController {
    private final UserSafetyApplicationService safety;
    private final ActorContext actors;

    public UserSafetyController(UserSafetyApplicationService safety, ActorContext actors) {
        this.safety = safety;
        this.actors = actors;
    }

    @PostMapping("/reports")
    public ResponseEntity<UserSafetyApplicationService.ReportView> report(
            HttpServletRequest http,
            @RequestBody UserSafetyApplicationService.ReportRequest request) {
        var result = safety.createReport(actors.requireActor(http), request);
        return ResponseEntity.created(URI.create("/api/v1/reports/" + result.id())).body(result);
    }

    @GetMapping("/users/{userId}/block")
    public ResponseEntity<UserSafetyApplicationService.BlockView> blockState(
            HttpServletRequest http,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(safety.getBlock(actors.requireActor(http), userId));
    }

    @PostMapping("/users/{userId}/block")
    public ResponseEntity<UserSafetyApplicationService.BlockView> block(
            HttpServletRequest http,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(safety.setBlocked(actors.requireActor(http), userId, true));
    }

    @DeleteMapping("/users/{userId}/block")
    public ResponseEntity<UserSafetyApplicationService.BlockView> unblock(
            HttpServletRequest http,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(safety.setBlocked(actors.requireActor(http), userId, false));
    }
}

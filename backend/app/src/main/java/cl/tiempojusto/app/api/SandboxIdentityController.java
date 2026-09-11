package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.IdentityApplicationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sandbox/identity")
@Profile({"dev", "test", "ci"})
public class SandboxIdentityController {
    private final IdentityApplicationService identity;

    public SandboxIdentityController(IdentityApplicationService identity) {
        this.identity = identity;
    }

    @PostMapping("/register")
    public ResponseEntity<IdentityApplicationService.RegistrationResult> register(
            @RequestBody IdentityApplicationService.RegistrationRequest request) {
        var result = identity.registerSandbox(request);
        return ResponseEntity.created(URI.create("/api/v1/sandbox/identity/users/" + result.userId())).body(result);
    }

    @PostMapping("/users/{userId}/verify-adult")
    public ResponseEntity<IdentityApplicationService.VerificationResult> verifyAdult(
            @PathVariable UUID userId,
            @RequestBody IdentityApplicationService.VerificationRequest request) {
        return ResponseEntity.ok(identity.verifySandboxAdult(userId, request));
    }
}

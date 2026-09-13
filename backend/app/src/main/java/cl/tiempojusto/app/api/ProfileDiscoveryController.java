package cl.tiempojusto.app.api;

import cl.tiempojusto.app.application.ProfileDiscoveryApplicationService;
import cl.tiempojusto.app.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ProfileDiscoveryController {
    private final ProfileDiscoveryApplicationService profiles;
    private final ActorContext actors;

    public ProfileDiscoveryController(ProfileDiscoveryApplicationService profiles, ActorContext actors) {
        this.profiles = profiles;
        this.actors = actors;
    }

    @GetMapping("/profiles/{profileId}")
    public ResponseEntity<ProfileDiscoveryApplicationService.ProfileView> profile(
            HttpServletRequest http,
            @PathVariable UUID profileId) {
        return ResponseEntity.ok(profiles.getPublicProfile(actors.requireActor(http), profileId));
    }

    @GetMapping("/discovery")
    public ResponseEntity<ProfileDiscoveryApplicationService.DiscoveryPage> discovery(
            HttpServletRequest http,
            @RequestParam(defaultValue = "ONLINE") String modality,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(profiles.discover(actors.requireActor(http), modality, limit));
    }
}

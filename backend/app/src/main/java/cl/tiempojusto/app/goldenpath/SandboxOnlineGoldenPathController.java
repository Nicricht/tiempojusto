package cl.tiempojusto.app.goldenpath;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/sandbox/golden-path")
@Profile({"dev", "test", "ci"})
public class SandboxOnlineGoldenPathController {

    private final SandboxOnlineGoldenPathService service;

    public SandboxOnlineGoldenPathController(SandboxOnlineGoldenPathService service) {
        this.service = service;
    }

    @PostMapping("/online")
    public ResponseEntity<SandboxOnlineGoldenPathService.Result> run(
            @RequestBody SandboxOnlineGoldenPathService.Request request) {
        return ResponseEntity.ok(service.run(request));
    }
}

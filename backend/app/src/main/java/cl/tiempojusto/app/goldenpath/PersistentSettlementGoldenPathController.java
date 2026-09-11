package cl.tiempojusto.app.goldenpath;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("ci")
@RequestMapping("/internal/ci/golden-path")
public class PersistentSettlementGoldenPathController {
    private final PersistentSettlementGoldenPathService service;

    public PersistentSettlementGoldenPathController(PersistentSettlementGoldenPathService service) {
        this.service = service;
    }

    @PostMapping("/settlement")
    public ResponseEntity<Map<String, Object>> run() {
        return ResponseEntity.ok(service.run());
    }
}

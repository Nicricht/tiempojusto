package cl.tiempojusto.app.goldenpath;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/ci/golden-path")
@Profile("ci")
public class ReconnectGoldenPathController {
    private final ReconnectGoldenPathService reconnect;

    public ReconnectGoldenPathController(ReconnectGoldenPathService reconnect) {
        this.reconnect = reconnect;
    }

    @PostMapping("/online-reconnect")
    public ResponseEntity<Map<String, Object>> run() {
        return ResponseEntity.ok(reconnect.run());
    }
}

package cl.tiempojusto.app.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthzController {

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }
}

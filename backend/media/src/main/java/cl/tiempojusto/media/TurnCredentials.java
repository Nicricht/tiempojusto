package cl.tiempojusto.media;

import java.time.Instant;
import java.util.List;

public record TurnCredentials(List<String> urls, String username, String credential, Instant expiresAt) {
    public TurnCredentials {
        urls = List.copyOf(urls);
        if (urls.isEmpty()) throw new IllegalArgumentException("TURN urls required");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("username required");
        if (credential == null || credential.isBlank()) throw new IllegalArgumentException("credential required");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt required");
    }
}

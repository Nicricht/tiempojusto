package cl.tiempojusto.app.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public interface ActorContext {
    UUID requireActor(HttpServletRequest request);
}

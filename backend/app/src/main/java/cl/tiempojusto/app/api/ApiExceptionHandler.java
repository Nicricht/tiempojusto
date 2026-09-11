package cl.tiempojusto.app.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<ErrorBody> handle(ApiProblem ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorBody> handleIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(409).body(new ErrorBody(
                "DATA_INTEGRITY_VIOLATION",
                "La operación viola una regla de integridad persistida.",
                Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_ARGUMENT", ex.getMessage(), Instant.now()));
    }

    public record ErrorBody(String code, String message, Instant timestamp) {}
}

package cl.tiempojusto.app.api;

import org.springframework.http.HttpStatus;

public final class ApiProblem extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private ApiProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiProblem badRequest(String code, String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiProblem unauthorized(String code, String message) {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiProblem forbidden(String code, String message) {
        return new ApiProblem(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiProblem notFound(String code, String message) {
        return new ApiProblem(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiProblem conflict(String code, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, code, message);
    }

    public static ApiProblem unavailable(String code, String message) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}

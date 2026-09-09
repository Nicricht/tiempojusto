package cl.tiempojusto.statemachine.common;

public final class TransitionException extends RuntimeException {
    private final String code;
    public TransitionException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
    public static TransitionException of(String code, String message) { return new TransitionException(code, message); }
}

package cl.tiempojusto.media;

public final class MediaException extends RuntimeException {
    private final String code;
    private MediaException(String code, String message) { super(message); this.code = code; }
    public static MediaException of(String code, String message) { return new MediaException(code, message); }
    public String code() { return code; }
}

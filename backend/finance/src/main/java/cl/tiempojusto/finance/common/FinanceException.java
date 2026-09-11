package cl.tiempojusto.finance.common;

public final class FinanceException extends RuntimeException {
    private final String code;

    public FinanceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

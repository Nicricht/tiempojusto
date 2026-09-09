package cl.tiempojusto.statemachine.extension;

import cl.tiempojusto.statemachine.common.TransitionException;

public record ExtensionNegotiation(ExtensionState state, int round, int minutes, long amountClp) {
    public static ExtensionNegotiation none() { return new ExtensionNegotiation(ExtensionState.NONE, 0, 0, 0); }

    public ExtensionNegotiation propose(int minutes, long amountClp, boolean sessionActive, boolean otherNegotiationExists) {
        if (state != ExtensionState.NONE) throw TransitionException.of("EXTENSION_BAD_STATE", "Negotiation already started");
        if (!sessionActive || otherNegotiationExists) throw TransitionException.of("EXTENSION_NOT_ALLOWED", "Session must be active and only one negotiation may exist");
        validate(minutes, amountClp);
        return new ExtensionNegotiation(ExtensionState.NEGOTIATING, 1, minutes, amountClp);
    }

    public ExtensionNegotiation counter(int minutes, long amountClp) {
        if (state != ExtensionState.NEGOTIATING) throw TransitionException.of("EXTENSION_BAD_STATE", "Counter requires active negotiation");
        if (round >= 3) throw TransitionException.of("EXTENSION_ROUNDS_EXHAUSTED", "Maximum 3 offer/counteroffer rounds");
        validate(minutes, amountClp);
        return new ExtensionNegotiation(ExtensionState.NEGOTIATING, round+1, minutes, amountClp);
    }

    public ExtensionNegotiation accept(boolean bilateralAcceptance, boolean fundsOk) {
        if (state != ExtensionState.NEGOTIATING) throw TransitionException.of("EXTENSION_BAD_STATE", "Accept requires active negotiation");
        if (!bilateralAcceptance) throw TransitionException.of("EXTENSION_NOT_BILATERAL", "Extension requires bilateral acceptance");
        if (!fundsOk) throw TransitionException.of("EXTENSION_FUNDS_FAILED", "Funds reservation must succeed before extension is confirmed");
        return new ExtensionNegotiation(ExtensionState.ACCEPTED, round, minutes, amountClp);
    }

    public ExtensionNegotiation reject() {
        if (state != ExtensionState.NEGOTIATING) throw TransitionException.of("EXTENSION_BAD_STATE", "Reject requires active negotiation");
        return new ExtensionNegotiation(ExtensionState.REJECTED, round, minutes, amountClp);
    }

    public ExtensionNegotiation expireBecauseSessionEnded(boolean sessionEnded) {
        if (state != ExtensionState.NEGOTIATING) throw TransitionException.of("EXTENSION_BAD_STATE", "Expire requires active negotiation");
        if (!sessionEnded) throw TransitionException.of("EXTENSION_EXPIRY_NOT_DUE", "V1.7 does not define a standalone negotiation timeout; session end can expire it");
        return new ExtensionNegotiation(ExtensionState.EXPIRED, round, minutes, amountClp);
    }

    private static void validate(int minutes, long amount) {
        if (!(minutes==15 || minutes==30)) throw TransitionException.of("EXTENSION_DURATION_INVALID", "Extension must be +15 or +30 minutes");
        if (amount < 0 || amount % 5_000 != 0) throw TransitionException.of("EXTENSION_AMOUNT_INVALID", "Amount must be a non-negative multiple of 5000");
    }
}

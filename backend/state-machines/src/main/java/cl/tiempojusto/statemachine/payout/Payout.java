package cl.tiempojusto.statemachine.payout;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record Payout(PayoutState state, Instant createdAt, Instant holdUntil) {
    public static Payout pending(Instant now) { return new Payout(PayoutState.PENDING, now, now.plusSeconds(3600)); }
    public Payout release(Instant now, boolean objectiveIncident) {
        if (state != PayoutState.PENDING) throw TransitionException.of("PAYOUT_BAD_STATE", "Only PENDING payout can leave hold");
        if (objectiveIncident) return new Payout(PayoutState.HELD_FOR_REVIEW, createdAt, holdUntil);
        if (now.isBefore(holdUntil)) throw TransitionException.of("PAYOUT_HOLD_ACTIVE", "Payout cannot become AVAILABLE before 60-minute hold");
        return new Payout(PayoutState.AVAILABLE, createdAt, holdUntil);
    }
    public Payout holdForReview(boolean objectiveIncident) {
        if (state != PayoutState.PENDING) throw TransitionException.of("PAYOUT_BAD_STATE", "Only PENDING payout can be held for review");
        if (!objectiveIncident) throw TransitionException.of("PAYOUT_NO_INCIDENT", "HELD_FOR_REVIEW requires objective incident");
        return new Payout(PayoutState.HELD_FOR_REVIEW, createdAt, holdUntil);
    }
}

package cl.tiempojusto.statemachine.safety;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record Appeal(AppealOutcome outcome, SafetyLevel originalLevel, SafetyLevel finalLevel, Instant submittedAt, Instant resolvedAt) {
    public static Appeal open(SafetyCase safetyCase, Instant now) {
        if (safetyCase.state() != SafetyCaseState.CONFIRMED) throw TransitionException.of("APPEAL_NO_SANCTION", "Only a confirmed safety outcome can be appealed");
        if (safetyCase.appealDeadline() == null || now.isAfter(safetyCase.appealDeadline())) throw TransitionException.of("APPEAL_LATE", "Appeal must be submitted within 7 calendar days");
        return new Appeal(AppealOutcome.OPEN, safetyCase.level(), safetyCase.level(), now, null);
    }
    public Appeal maintain(Instant now) { requireOpen(); return new Appeal(AppealOutcome.MAINTAIN, originalLevel, originalLevel, submittedAt, now); }
    public Appeal reduce(SafetyLevel reducedLevel, Instant now) {
        requireOpen();
        if (reducedLevel.ordinal() >= originalLevel.ordinal()) throw TransitionException.of("APPEAL_NOT_REDUCTION", "Reduced level must be lower than original");
        return new Appeal(AppealOutcome.REDUCE, originalLevel, reducedLevel, submittedAt, now);
    }
    public Appeal revoke(Instant now) { requireOpen(); return new Appeal(AppealOutcome.REVOKE, originalLevel, SafetyLevel.S0, submittedAt, now); }
    private void requireOpen(){ if (outcome != AppealOutcome.OPEN) throw TransitionException.of("APPEAL_BAD_STATE", "Appeal is already resolved"); }
}

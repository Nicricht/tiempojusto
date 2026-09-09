package cl.tiempojusto.statemachine.safety;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record SafetyCase(SafetyCaseState state, SafetyLevel level, Instant decidedAt, Instant appealDeadline) {
    public static SafetyCase reported() { return new SafetyCase(SafetyCaseState.REPORTED, SafetyLevel.S0, null, null); }
    public SafetyCase triage(boolean validCase) {
        if (state != SafetyCaseState.REPORTED) throw TransitionException.of("SAFETY_BAD_STATE", "Triage requires REPORTED");
        if (!validCase) return new SafetyCase(SafetyCaseState.DISMISSED, SafetyLevel.S0, null, null);
        return new SafetyCase(SafetyCaseState.UNDER_REVIEW, SafetyLevel.S0, null, null);
    }
    public SafetyCase confirm(SafetyLevel level, Instant now, boolean humanApprovedForS5) {
        if (state != SafetyCaseState.UNDER_REVIEW) throw TransitionException.of("SAFETY_BAD_STATE", "Confirmation requires UNDER_REVIEW");
        if (level == SafetyLevel.S0) throw TransitionException.of("SAFETY_LEVEL_INVALID", "Confirmed infringement cannot use S0");
        if (level == SafetyLevel.S5 && !humanApprovedForS5) throw TransitionException.of("SAFETY_S5_HUMAN_REQUIRED", "S5 irreversible/indefinite action requires HumanReviewQueue approval");
        return new SafetyCase(SafetyCaseState.CONFIRMED, level, now, now.plusSeconds(7L*24*3600));
    }
    public SafetyCase undetermined() {
        if (state != SafetyCaseState.UNDER_REVIEW) throw TransitionException.of("SAFETY_BAD_STATE", "UNDETERMINED requires UNDER_REVIEW");
        return new SafetyCase(SafetyCaseState.UNDETERMINED, SafetyLevel.S0, null, null);
    }
    public SafetyCase dismiss() {
        if (state != SafetyCaseState.UNDER_REVIEW) throw TransitionException.of("SAFETY_BAD_STATE", "DISMISSED requires UNDER_REVIEW");
        return new SafetyCase(SafetyCaseState.DISMISSED, SafetyLevel.S0, null, null);
    }
}

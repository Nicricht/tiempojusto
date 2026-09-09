package cl.tiempojusto.statemachine.meta;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record MetaNow(MetaNowState state, long targetClp, long highestCompatibleClp, Instant expiresAt) {
    public static MetaNow create(long targetClp, long highestCompatibleClp, Instant now, int windowMinutes) {
        if (targetClp < 10_000 || targetClp % 5_000 != 0) throw TransitionException.of("META_TARGET_INVALID", "Target must be >= 10000 and multiple of 5000");
        if (!(windowMinutes==30 || windowMinutes==60 || windowMinutes==120 || windowMinutes==240)) throw TransitionException.of("META_WINDOW_INVALID", "Window must be 30, 60, 120 or 240 minutes");
        return new MetaNow(MetaNowState.ACTIVE, targetClp, highestCompatibleClp, now.plusSeconds(windowMinutes*60L));
    }
    public MetaNow updateHighest(long amount, Instant now) {
        requireActive(now);
        MetaNow m = new MetaNow(state, targetClp, Math.max(0, amount), expiresAt);
        return m.highestCompatibleClp >= targetClp ? m.trigger(now, true) : m;
    }
    public MetaNow trigger(Instant now, boolean availabilityValid) {
        requireActive(now);
        if (!availabilityValid) throw TransitionException.of("META_AVAILABILITY_INVALID", "Now availability must remain valid");
        if (highestCompatibleClp < targetClp) throw TransitionException.of("META_NOT_REACHED", "Highest compatible Proposal has not reached target");
        return new MetaNow(MetaNowState.TRIGGERED, targetClp, highestCompatibleClp, expiresAt);
    }
    public MetaNow cancel(Instant now) {
        requireActive(now);
        if (highestCompatibleClp >= targetClp) throw TransitionException.of("META_ALREADY_REACHED", "Cannot freely cancel after target reaches 100%");
        return new MetaNow(MetaNowState.CANCELLED, targetClp, highestCompatibleClp, expiresAt);
    }
    public MetaNow expire(Instant now) {
        if (state != MetaNowState.ACTIVE) throw TransitionException.of("META_BAD_STATE", "Only active MetaNow can expire");
        if (now.isBefore(expiresAt)) throw TransitionException.of("META_NOT_DUE", "MetaNow expiration deadline has not been reached");
        if (highestCompatibleClp >= targetClp) throw TransitionException.of("META_SHOULD_TRIGGER", "Reached MetaNow must trigger Auction rather than expire");
        return new MetaNow(MetaNowState.EXPIRED, targetClp, highestCompatibleClp, expiresAt);
    }
    public int progressPercent() { return (int)Math.min(100, Math.floor((highestCompatibleClp * 100.0) / targetClp)); }
    private void requireActive(Instant now) {
        if (state != MetaNowState.ACTIVE) throw TransitionException.of("META_BAD_STATE", "MetaNow is not active");
        if (!now.isBefore(expiresAt)) throw TransitionException.of("META_EXPIRED", "MetaNow is expired");
    }
}

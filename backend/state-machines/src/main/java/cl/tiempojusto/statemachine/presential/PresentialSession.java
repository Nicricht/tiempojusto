package cl.tiempojusto.statemachine.presential;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record PresentialSession(PresentialSessionState state, Instant freeStartedAt, Instant freeEndsAt, Instant paidStartedAt, Instant endedAt) {
    public static PresentialSession startFree(Instant now, boolean validHandshake) {
        if (!validHandshake) throw TransitionException.of("FREE_NO_HANDSHAKE", "FREE_PRESENTIAL requires valid bilateral MeetingHandshake");
        return new PresentialSession(PresentialSessionState.FREE, now, now.plusSeconds(300), null, null);
    }
    public PresentialSession freeTimeout(Instant now) {
        if (state != PresentialSessionState.FREE) throw TransitionException.of("SESSION_BAD_STATE", "Session is not FREE");
        if (now.isBefore(freeEndsAt)) throw TransitionException.of("FREE_NOT_DUE", "Free period has not reached 5 minutes");
        return new PresentialSession(PresentialSessionState.PAID_ACTIVE, freeStartedAt, freeEndsAt, now, null);
    }
    public PresentialSession pause() {
        if (state != PresentialSessionState.PAID_ACTIVE) throw TransitionException.of("PAUSE_BAD_STATE", "Only PAID_ACTIVE can pause");
        return new PresentialSession(PresentialSessionState.PAUSED, freeStartedAt, freeEndsAt, paidStartedAt, endedAt);
    }
    public PresentialSession resume(boolean hostAccepts, boolean bidderAccepts, Instant now) {
        if (state != PresentialSessionState.PAUSED) throw TransitionException.of("RESUME_BAD_STATE", "Only PAUSED can resume");
        if (!(hostAccepts && bidderAccepts)) throw TransitionException.of("RESUME_NOT_BILATERAL", "Resume requires bilateral acceptance");
        return new PresentialSession(PresentialSessionState.PAID_ACTIVE, freeStartedAt, freeEndsAt, now, null);
    }
    public PresentialSession finish(Instant now) {
        if (state == PresentialSessionState.FINISHED) return this;
        return new PresentialSession(PresentialSessionState.FINISHED, freeStartedAt, freeEndsAt, paidStartedAt, now);
    }
    public boolean billable() { return state == PresentialSessionState.PAID_ACTIVE; }
}

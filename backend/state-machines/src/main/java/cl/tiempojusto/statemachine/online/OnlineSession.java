package cl.tiempojusto.statemachine.online;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record OnlineSession(OnlineState state, Instant joinDeadline, Instant freeEndsAt, Instant paidConsentDeadline, Instant reconnectDeadline, Instant lastValidMediaAt, boolean hostPaidAccepted, boolean bidderPaidAccepted) {
    public static OnlineSession winnerSelected() { return new OnlineSession(OnlineState.WINNER_SELECTED, null, null, null, null, null, false, false); }
    public OnlineSession openJoinWindow(Instant now) {
        if (state != OnlineState.WINNER_SELECTED) throw TransitionException.of("ONLINE_BAD_STATE", "Join window requires WINNER_SELECTED");
        return new OnlineSession(OnlineState.JOIN_WINDOW, now.plusSeconds(180), null, null, null, null, false, false);
    }
    public OnlineSession bothConnected(Instant now, boolean hostCameraValid, boolean bidderCameraValid) {
        if (state != OnlineState.JOIN_WINDOW) throw TransitionException.of("ONLINE_BAD_STATE", "Both-connected requires JOIN_WINDOW");
        if (now.isAfter(joinDeadline)) throw TransitionException.of("ONLINE_JOIN_TIMEOUT", "Both participants must join within 3 minutes");
        if (!(hostCameraValid && bidderCameraValid)) throw TransitionException.of("ONLINE_CAMERA_REQUIRED", "Camera/media must be valid for both participants");
        return new OnlineSession(OnlineState.FREE_ONLINE, joinDeadline, now.plusSeconds(120), null, null, now, false, false);
    }
    public OnlineSession freeTimeout(Instant now, boolean bothStillConnected) {
        if (state != OnlineState.FREE_ONLINE) throw TransitionException.of("ONLINE_BAD_STATE", "Free timeout requires FREE_ONLINE");
        if (now.isBefore(freeEndsAt)) throw TransitionException.of("ONLINE_FREE_NOT_DUE", "FREE_ONLINE has not reached 2 minutes");
        if (!bothStillConnected) return new OnlineSession(OnlineState.FINISHED_FREE, joinDeadline, freeEndsAt, null, null, lastValidMediaAt, false, false);
        return new OnlineSession(OnlineState.AWAITING_PAID_CONFIRMATION, joinDeadline, freeEndsAt, now.plusSeconds(30), null, lastValidMediaAt, false, false);
    }
    public OnlineSession acceptPaid(boolean host, Instant now, boolean fundsValid) {
        if (state != OnlineState.AWAITING_PAID_CONFIRMATION) throw TransitionException.of("ONLINE_BAD_STATE", "Paid consent requires AWAITING_PAID_CONFIRMATION");
        if (now.isAfter(paidConsentDeadline)) throw TransitionException.of("ONLINE_PAID_CONSENT_TIMEOUT", "30-second consent window expired");
        if (!fundsValid) throw TransitionException.of("ONLINE_FUNDS_INVALID", "Funds reservation must remain valid");
        boolean h = hostPaidAccepted || host;
        boolean b = bidderPaidAccepted || !host;
        OnlineState next = h && b ? OnlineState.PAID_ACTIVE : state;
        return new OnlineSession(next, joinDeadline, freeEndsAt, paidConsentDeadline, null, now, h, b);
    }
    public OnlineSession paidConsentTimeout(Instant now) {
        if (state != OnlineState.AWAITING_PAID_CONFIRMATION) throw TransitionException.of("ONLINE_BAD_STATE", "Consent timeout requires AWAITING_PAID_CONFIRMATION");
        if (now.isBefore(paidConsentDeadline)) throw TransitionException.of("ONLINE_NOT_DUE", "Consent timeout not due");
        return new OnlineSession(OnlineState.FINISHED_FREE, joinDeadline, freeEndsAt, paidConsentDeadline, null, lastValidMediaAt, hostPaidAccepted, bidderPaidAccepted);
    }
    public OnlineSession mediaHeartbeat(Instant now) {
        if (!(state == OnlineState.PAID_ACTIVE || state == OnlineState.RECONNECTING)) return this;
        return new OnlineSession(state, joinDeadline, freeEndsAt, paidConsentDeadline, reconnectDeadline, now, hostPaidAccepted, bidderPaidAccepted);
    }
    public OnlineSession confirmMediaInterruption(Instant now) {
        if (state != OnlineState.PAID_ACTIVE) throw TransitionException.of("ONLINE_BAD_STATE", "Media interruption requires PAID_ACTIVE");
        if (lastValidMediaAt == null || now.isBefore(lastValidMediaAt.plusSeconds(5))) throw TransitionException.of("ONLINE_MICROCUT_TOLERANCE", "Interruption must exceed 5-second tolerance");
        return new OnlineSession(OnlineState.RECONNECTING, joinDeadline, freeEndsAt, paidConsentDeadline, now.plusSeconds(120), lastValidMediaAt, false, false);
    }
    public OnlineSession resumeAfterReconnect(Instant now, boolean mediaValid, boolean hostAccepts, boolean bidderAccepts) {
        if (state != OnlineState.RECONNECTING) throw TransitionException.of("ONLINE_BAD_STATE", "Reconnect resume requires RECONNECTING");
        if (now.isAfter(reconnectDeadline)) throw TransitionException.of("ONLINE_RECONNECT_TIMEOUT", "Reconnect window expired");
        if (!mediaValid || !(hostAccepts && bidderAccepts)) throw TransitionException.of("ONLINE_RECONNECT_GUARD", "Media recovery and bilateral acceptance are required");
        return new OnlineSession(OnlineState.PAID_ACTIVE, joinDeadline, freeEndsAt, paidConsentDeadline, null, now, true, true);
    }
    public OnlineSession reconnectTimeout(Instant now) {
        if (state != OnlineState.RECONNECTING) throw TransitionException.of("ONLINE_BAD_STATE", "Reconnect timeout requires RECONNECTING");
        if (now.isBefore(reconnectDeadline)) throw TransitionException.of("ONLINE_NOT_DUE", "Reconnect timeout not due");
        return new OnlineSession(OnlineState.FINISHED_RECONNECT_TIMEOUT, joinDeadline, freeEndsAt, paidConsentDeadline, reconnectDeadline, lastValidMediaAt, hostPaidAccepted, bidderPaidAccepted);
    }
    public OnlineSession finish() { return new OnlineSession(OnlineState.FINISHED, joinDeadline, freeEndsAt, paidConsentDeadline, reconnectDeadline, lastValidMediaAt, hostPaidAccepted, bidderPaidAccepted); }
    public boolean billable() { return state == OnlineState.PAID_ACTIVE; }
}

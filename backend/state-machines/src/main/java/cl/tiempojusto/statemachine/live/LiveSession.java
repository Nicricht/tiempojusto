package cl.tiempojusto.statemachine.live;

import java.time.Duration;
import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record LiveSession(LiveState state, Instant reconnectDeadline, boolean firstTicketSold) {
    public static LiveSession start(Instant now, Instant auctionDeadline, boolean auctionActive) {
        if (!auctionActive) throw TransitionException.of("LIVE_NO_ACTIVE_AUCTION", "Live requires an active Auction");
        if (Duration.between(now, auctionDeadline).getSeconds() < 300) throw TransitionException.of("LIVE_TOO_LATE", "A new Live requires at least 5:00 remaining");
        return new LiveSession(LiveState.LIVE, null, false);
    }
    public LiveSession markTicketSold() {
        if (state != LiveState.LIVE) throw TransitionException.of("LIVE_BAD_STATE", "Ticket can only be sold while Live is active");
        return new LiveSession(state, reconnectDeadline, true);
    }
    public LiveSession connectionLost(Instant now) {
        if (state != LiveState.LIVE) throw TransitionException.of("LIVE_BAD_STATE", "Connection loss requires LIVE");
        return new LiveSession(LiveState.RECONNECTING, now.plusSeconds(120), firstTicketSold);
    }
    public LiveSession reconnect(Instant now) {
        if (state != LiveState.RECONNECTING) throw TransitionException.of("LIVE_BAD_STATE", "Reconnect requires RECONNECTING");
        if (now.isAfter(reconnectDeadline)) throw TransitionException.of("LIVE_RECONNECT_TIMEOUT", "Live reconnect window expired");
        return new LiveSession(LiveState.LIVE, null, firstTicketSold);
    }
    public LiveSession reconnectTimeout(Instant now) {
        if (state != LiveState.RECONNECTING) throw TransitionException.of("LIVE_BAD_STATE", "Timeout requires RECONNECTING");
        if (now.isBefore(reconnectDeadline)) throw TransitionException.of("LIVE_NOT_DUE", "Reconnect timeout not due");
        return new LiveSession(LiveState.FAILED, reconnectDeadline, firstTicketSold);
    }
    public LiveSession auctionClosed() { return new LiveSession(LiveState.ENDED, null, firstTicketSold); }
    public static int ticketPriceClp(long remainingSeconds) {
        if (remainingSeconds < 180) throw TransitionException.of("TICKET_CUTOFF", "No new Ticket sales below 3:00");
        return remainingSeconds >= 480 ? 1500 : 1000;
    }
}

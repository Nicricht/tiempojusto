package cl.tiempojusto.statemachine.auction;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record Auction(AuctionState state, Instant startedAt, Instant deadline, long currentAmountClp, Long closeNowClp, long acceptedBidCount) {
    public static Auction open(Instant now, long openingAmountClp, Long closeNowClp) {
        validateMoney(openingAmountClp);
        if (closeNowClp != null && (closeNowClp <= openingAmountClp || closeNowClp % 5_000 != 0)) throw TransitionException.of("AUCTION_CLOSE_NOW_INVALID", "Close Now must be greater than opening and multiple of 5000");
        return new Auction(AuctionState.OPEN, now, now.plusSeconds(15*60L), openingAmountClp, closeNowClp, 0);
    }
    public Auction acceptBid(long amountClp, Instant committedAt, boolean fundingOk, boolean eligible) {
        requireRunning(committedAt);
        if (!fundingOk) throw TransitionException.of("BID_FUNDING_FAILED", "Funding/risk must succeed before BID_ACCEPTED");
        if (!eligible) throw TransitionException.of("BID_INELIGIBLE", "Bidder is not eligible");
        validateMoney(amountClp);
        if (amountClp <= currentAmountClp) throw TransitionException.of("BID_TOO_LOW", "Bid must exceed current amount");
        Instant newDeadline = deadline;
        AuctionState newState = state;
        long remaining = deadline.getEpochSecond() - committedAt.getEpochSecond();
        if (remaining <= 120) {
            newDeadline = committedAt.plusSeconds(120);
            newState = AuctionState.EXTENDED;
        }
        return new Auction(newState, startedAt, newDeadline, amountClp, closeNowClp, acceptedBidCount+1);
    }
    public Auction closeNatural(Instant now) {
        if (!(state == AuctionState.OPEN || state == AuctionState.EXTENDED)) throw TransitionException.of("AUCTION_BAD_STATE", "Auction is not running");
        if (now.isBefore(deadline)) throw TransitionException.of("AUCTION_NOT_DUE", "Auction timer has not reached zero");
        return new Auction(AuctionState.CLOSED, startedAt, deadline, currentAmountClp, closeNowClp, acceptedBidCount);
    }
    public Auction closeNow(Instant now, long amountClp, boolean fundingOk, boolean eligible) {
        requireRunning(now);
        if (closeNowClp == null) throw TransitionException.of("AUCTION_CLOSE_NOW_DISABLED", "Close Now was not configured");
        if (!fundingOk || !eligible) throw TransitionException.of("AUCTION_CLOSE_NOW_GUARD", "Close Now requires eligible bidder and valid funds");
        if (amountClp != closeNowClp) throw TransitionException.of("AUCTION_CLOSE_NOW_AMOUNT", "Close Now amount must match frozen amount");
        return new Auction(AuctionState.CLOSED_NOW, startedAt, now, amountClp, closeNowClp, acceptedBidCount+1);
    }
    public boolean closeNowVisible(long nextMinimumBid) { return closeNowClp != null && nextMinimumBid < closeNowClp; }
    private void requireRunning(Instant now) {
        if (!(state == AuctionState.OPEN || state == AuctionState.EXTENDED)) throw TransitionException.of("AUCTION_BAD_STATE", "Auction is not running");
        if (now.isAfter(deadline)) throw TransitionException.of("AUCTION_DEADLINE_PASSED", "Auction deadline passed");
    }
    private static void validateMoney(long amount) {
        if (amount < 0 || amount % 5_000 != 0) throw TransitionException.of("AUCTION_AMOUNT_INVALID", "Amount must be non-negative multiple of 5000");
    }
}

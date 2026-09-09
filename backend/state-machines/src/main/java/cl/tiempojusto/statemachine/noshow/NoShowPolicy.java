package cl.tiempojusto.statemachine.noshow;

import cl.tiempojusto.statemachine.common.TransitionException;

public final class NoShowPolicy {
    private NoShowPolicy() {}

    public enum BidderStage { NO_CONFIRM_3_MIN, CONFIRMED_CANCEL_BEFORE_ARRIVAL, CONFIRMED_NO_SHOW }
    public record BidderConsequence(int penaltyPercent, boolean loseAward, boolean suspendBiddingAndReview) {}
    public record HostConsequence(int refundPercentToWinner, int hostRevenuePercent, int suspensionHours, boolean review) {}

    public static BidderConsequence bidder(BidderStage stage, int priorConfirmedNoShowsWithin90Days) {
        if (priorConfirmedNoShowsWithin90Days < 0) throw TransitionException.of("NO_SHOW_COUNT_INVALID", "Prior no-show count cannot be negative");
        return switch(stage) {
            case NO_CONFIRM_3_MIN -> new BidderConsequence(10, true, false);
            case CONFIRMED_CANCEL_BEFORE_ARRIVAL -> new BidderConsequence(15, true, false);
            case CONFIRMED_NO_SHOW -> {
                if (priorConfirmedNoShowsWithin90Days == 0) yield new BidderConsequence(20, true, false);
                if (priorConfirmedNoShowsWithin90Days == 1) yield new BidderConsequence(35, true, false);
                yield new BidderConsequence(50, true, true);
            }
        };
    }

    public static HostConsequence hostConfirmedNoShow(int priorConfirmedHostNoShowsWithin90Days) {
        if (priorConfirmedHostNoShowsWithin90Days < 0) throw TransitionException.of("NO_SHOW_COUNT_INVALID", "Prior no-show count cannot be negative");
        if (priorConfirmedHostNoShowsWithin90Days == 0) return new HostConsequence(100, 0, 24, false);
        if (priorConfirmedHostNoShowsWithin90Days == 1) return new HostConsequence(100, 0, 7*24, false);
        return new HostConsequence(100, 0, 30*24, true);
    }
}

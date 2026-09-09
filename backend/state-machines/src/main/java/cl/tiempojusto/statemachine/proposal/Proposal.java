package cl.tiempojusto.statemachine.proposal;

import java.time.Instant;
import cl.tiempojusto.statemachine.common.TransitionException;

public record Proposal(ProposalState state, long amountClp, Instant createdAt, Instant expiresAt, Instant withdrawnAt, Instant cooldownUntil) {
    public static Proposal activate(long amountClp, Instant now, boolean accountEligible, boolean duplicateActive) {
        if (!accountEligible) throw TransitionException.of("PROPOSAL_ACCOUNT_INELIGIBLE", "Account is not eligible");
        validateAmount(amountClp);
        if (duplicateActive) throw TransitionException.of("PROPOSAL_DUPLICATE_ACTIVE", "Another active Proposal exists for the same combination");
        return new Proposal(ProposalState.ACTIVE, amountClp, now, now.plusSeconds(7L*24*3600), null, null);
    }
    public Proposal update(long newAmountClp, Instant now) {
        requireLive(now);
        validateAmount(newAmountClp);
        return new Proposal(ProposalState.UPDATED, newAmountClp, createdAt, expiresAt, withdrawnAt, cooldownUntil);
    }
    public Proposal normalizeUpdated() {
        if (state != ProposalState.UPDATED) return this;
        return new Proposal(ProposalState.ACTIVE, amountClp, createdAt, expiresAt, withdrawnAt, cooldownUntil);
    }
    public Proposal withdraw(Instant now, boolean alreadyBid) {
        requireLive(now);
        if (alreadyBid) throw TransitionException.of("PROPOSAL_ALREADY_BID", "A Proposal cannot be withdrawn as if it were a Bid");
        return new Proposal(ProposalState.WITHDRAWN, amountClp, createdAt, expiresAt, now, now.plusSeconds(24*3600));
    }
    public Proposal expire(Instant now) {
        if (!(state == ProposalState.ACTIVE || state == ProposalState.UPDATED)) throw TransitionException.of("PROPOSAL_BAD_STATE", "Only active Proposal can expire");
        if (now.isBefore(expiresAt)) throw TransitionException.of("PROPOSAL_NOT_DUE", "Proposal expiration deadline has not been reached");
        return new Proposal(ProposalState.EXPIRED, amountClp, createdAt, expiresAt, withdrawnAt, cooldownUntil);
    }
    public boolean countsInMetrics() { return state == ProposalState.ACTIVE || state == ProposalState.UPDATED; }
    private void requireLive(Instant now) {
        if (!(state == ProposalState.ACTIVE || state == ProposalState.UPDATED)) throw TransitionException.of("PROPOSAL_BAD_STATE", "Proposal is not active");
        if (!now.isBefore(expiresAt)) throw TransitionException.of("PROPOSAL_EXPIRED", "Proposal is expired");
    }
    private static void validateAmount(long amount) {
        if (amount < 10_000 || amount % 5_000 != 0) throw TransitionException.of("PROPOSAL_AMOUNT_INVALID", "Amount must be >= 10000 CLP and multiple of 5000");
    }
}

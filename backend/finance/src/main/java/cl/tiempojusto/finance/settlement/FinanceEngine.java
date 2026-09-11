package cl.tiempojusto.finance.settlement;

import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.ledger.InMemoryLedger;
import cl.tiempojusto.finance.ledger.InMemoryLedger.*;
import cl.tiempojusto.finance.payment.PaymentPort;
import cl.tiempojusto.finance.payment.PaymentPort.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FinanceEngine {
    public enum EarningState { PENDING, HELD_FOR_REVIEW, AVAILABLE, PAYOUT_REQUESTED, PAID_OUT }
    public enum SettlementKind { SESSION, EXTENSION, LIVE_TICKET }
    public record Earning(UUID id, UUID hostUserId, SettlementKind kind, UUID sourceReferenceId, UUID captureId, UUID ledgerTransactionId, long grossAmountClp, long platformAmountClp, long hostAmountClp, Instant generatedAt, Instant availableAt, EarningState state) {
        public Earning withState(EarningState newState) { return new Earning(id, hostUserId, kind, sourceReferenceId, captureId, ledgerTransactionId, grossAmountClp, platformAmountClp, hostAmountClp, generatedAt, availableAt, newState); }
    }
    public record SettlementResult(Earning earning, Capture capture, Reservation reservationAfterRelease) {}
    public record ChargebackCase(UUID id, UUID hostUserId, PaymentDispute dispute, boolean compliantHost, boolean automaticHostDebtCreated) {}
    public static final Duration STANDARD_HOLD = Duration.ofMinutes(60);

    private final PaymentPort paymentPort;
    private final InMemoryLedger ledger;
    private final Map<UUID, Earning> earnings = new HashMap<>();
    private final Map<String, SettlementOp> settlementIdempotency = new HashMap<>();
    private final Map<String, PayoutOp> payoutIdempotency = new HashMap<>();

    public FinanceEngine(PaymentPort paymentPort, InMemoryLedger ledger) {
        this.paymentPort = Objects.requireNonNull(paymentPort);
        this.ledger = Objects.requireNonNull(ledger);
    }

    public Reservation reserveBid(UUID payerUserId, long amountClp, String key, Instant now) {
        return paymentPort.reserve(payerUserId, amountClp, key, now);
    }

    public Reservation adjustBidReservation(UUID reservationId, long newAmountClp, String key, Instant now) {
        return paymentPort.adjustReservation(reservationId, newAmountClp, key, now);
    }

    public Reservation releaseBidReservation(UUID reservationId, String key, Instant now) {
        return paymentPort.releaseReservation(reservationId, key, now);
    }

    public synchronized SettlementResult settleSession(UUID hostUserId, UUID reservationId, long generatedAmountClp,
                                                       UUID sessionId, String key, Instant now) {
        return settleReserved(hostUserId, reservationId, generatedAmountClp, sessionId, SettlementKind.SESSION,
                80, key, now);
    }

    public synchronized SettlementResult settleExtension(UUID hostUserId, UUID reservationId, long generatedAmountClp,
                                                         UUID extensionId, String key, Instant now) {
        return settleReserved(hostUserId, reservationId, generatedAmountClp, extensionId, SettlementKind.EXTENSION,
                80, key, now);
    }

    public synchronized SettlementResult settleLiveTicket(UUID hostUserId, UUID payerUserId, long ticketAmountClp,
                                                          UUID ticketId, String key, Instant now) {
        requirePositive(ticketAmountClp);
        String fingerprint = "live|" + hostUserId + "|" + payerUserId + "|" + ticketAmountClp + "|" + ticketId;
        SettlementResult prior = priorSettlement(key, fingerprint);
        if (prior != null) return prior;

        Reservation reservation = paymentPort.reserve(payerUserId, ticketAmountClp, key + ":reserve", now);
        Capture capture = paymentPort.capture(reservation.id(), ticketAmountClp, key + ":capture", now);
        Reservation released = paymentPort.releaseReservation(reservation.id(), key + ":release", now);
        SettlementResult result = postSettlement(hostUserId, ticketId, SettlementKind.LIVE_TICKET, capture,
                ticketAmountClp, 70, key, now, released);
        rememberSettlement(key, fingerprint, result);
        return result;
    }

    public synchronized Earning releaseHold(UUID earningId, Instant now, String key) {
        Earning earning = requiredEarning(earningId);
        if (earning.state() == EarningState.AVAILABLE || earning.state() == EarningState.PAID_OUT) return earning;
        if (earning.state() == EarningState.HELD_FOR_REVIEW) throw error("PAYOUT_HELD_FOR_REVIEW", "Objective incident prevents automatic release");
        if (earning.state() != EarningState.PENDING) throw error("PAYOUT_STATE_INVALID", "Earning is not pending");
        if (now.isBefore(earning.availableAt())) throw error("PAYOUT_HOLD_NOT_EXPIRED", "60-minute hold has not expired");

        Account pending = userPending(earning.hostUserId());
        Account available = userAvailable(earning.hostUserId());
        ledger.post(TransactionType.HOLD_RELEASE, "EARNING", earning.id(), key, now, List.of(
                new Posting(pending, Direction.DEBIT, earning.hostAmountClp()),
                new Posting(available, Direction.CREDIT, earning.hostAmountClp())
        ));
        Earning updated = earning.withState(EarningState.AVAILABLE);
        earnings.put(updated.id(), updated);
        return updated;
    }

    public synchronized Earning holdForReview(UUID earningId) {
        Earning earning = requiredEarning(earningId);
        if (earning.state() != EarningState.PENDING) throw error("PAYOUT_STATE_INVALID", "Only PENDING earning can enter review hold");
        Earning updated = earning.withState(EarningState.HELD_FOR_REVIEW);
        earnings.put(updated.id(), updated);
        return updated;
    }

    public synchronized PayoutTransfer requestPayout(UUID earningId, String key, Instant now) {
        Earning earning = requiredEarning(earningId);
        String fingerprint = "payout|" + earningId + "|" + earning.hostAmountClp();
        PayoutOp prior = payoutIdempotency.get(key);
        if (prior != null) {
            if (!prior.fingerprint.equals(fingerprint)) throw error("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", "Payout key reused with different payload");
            return prior.transfer;
        }
        if (earning.state() != EarningState.AVAILABLE) throw error("PAYOUT_NOT_AVAILABLE", "Earning must be AVAILABLE before payout");
        earnings.put(earning.id(), earning.withState(EarningState.PAYOUT_REQUESTED));
        try {
            PayoutTransfer transfer = paymentPort.payout(earning.hostUserId(), earning.hostAmountClp(), key + ":provider", now);
            ledger.post(TransactionType.PAYOUT, "EARNING", earning.id(), key + ":ledger", now, List.of(
                    new Posting(userAvailable(earning.hostUserId()), Direction.DEBIT, earning.hostAmountClp()),
                    new Posting(providerClearing(), Direction.CREDIT, earning.hostAmountClp())
            ));
            earnings.put(earning.id(), earning.withState(EarningState.PAID_OUT));
            payoutIdempotency.put(key, new PayoutOp(fingerprint, transfer));
            return transfer;
        } catch (RuntimeException ex) {
            earnings.put(earning.id(), earning.withState(EarningState.AVAILABLE));
            throw ex;
        }
    }

    public synchronized Reservation hostNoShowRefund100(UUID reservationId, String key, Instant now) {
        return paymentPort.releaseReservation(reservationId, key, now);
    }

    public synchronized ChargebackCase openChargeback(UUID hostUserId, UUID captureId, long amountClp,
                                                      String providerReference, boolean compliantHost,
                                                      String key, Instant now) {
        PaymentDispute dispute = paymentPort.openDispute(captureId, amountClp, providerReference, key, now);
        return new ChargebackCase(UUID.randomUUID(), hostUserId, dispute, compliantHost, false);
    }

    public synchronized Transaction recordConfirmedFraudRecovery(UUID hostUserId, long amountClp,
                                                                 String evidenceReference, UUID caseId,
                                                                 String key, Instant now) {
        requirePositive(amountClp);
        if (evidenceReference == null || evidenceReference.isBlank()) {
            throw error("FRAUD_EVIDENCE_REQUIRED", "Confirmed fraud recovery requires objective evidence reference");
        }
        return ledger.post(TransactionType.CHARGEBACK, "FRAUD_RECOVERY", caseId, key, now, List.of(
                new Posting(userAvailable(hostUserId), Direction.DEBIT, amountClp),
                new Posting(platformDispute(), Direction.CREDIT, amountClp)
        ));
    }

    public synchronized Earning earning(UUID id) {
        return requiredEarning(id);
    }

    public InMemoryLedger ledger() {
        return ledger;
    }

    private SettlementResult settleReserved(UUID hostUserId, UUID reservationId, long generatedAmountClp,
                                            UUID referenceId, SettlementKind kind, int hostPercent,
                                            String key, Instant now) {
        requirePositive(generatedAmountClp);
        String fingerprint = kind + "|" + hostUserId + "|" + reservationId + "|" + generatedAmountClp + "|" + referenceId;
        SettlementResult prior = priorSettlement(key, fingerprint);
        if (prior != null) return prior;
        Capture capture = paymentPort.capture(reservationId, generatedAmountClp, key + ":capture", now);
        Reservation released = paymentPort.releaseReservation(reservationId, key + ":release-unused", now);
        SettlementResult result = postSettlement(hostUserId, referenceId, kind, capture, generatedAmountClp,
                hostPercent, key, now, released);
        rememberSettlement(key, fingerprint, result);
        return result;
    }

    private SettlementResult postSettlement(UUID hostUserId, UUID referenceId, SettlementKind kind,
                                            Capture capture, long grossAmountClp, int hostPercent,
                                            String key, Instant now, Reservation released) {
        long hostAmount = Math.floorDiv(Math.multiplyExact(grossAmountClp, hostPercent), 100);
        long platformAmount = grossAmountClp - hostAmount;
        TransactionType txType = kind == SettlementKind.LIVE_TICKET
                ? TransactionType.TICKET_SETTLEMENT : TransactionType.SESSION_SETTLEMENT;
        Transaction tx = ledger.post(txType, kind.name(), referenceId, key + ":ledger", now, List.of(
                new Posting(providerClearing(), Direction.DEBIT, grossAmountClp),
                new Posting(userPending(hostUserId), Direction.CREDIT, hostAmount),
                new Posting(platformRevenue(), Direction.CREDIT, platformAmount)
        ));
        Earning earning = new Earning(UUID.randomUUID(), hostUserId, kind, referenceId, capture.id(), tx.id(),
                grossAmountClp, platformAmount, hostAmount, now, now.plus(STANDARD_HOLD), EarningState.PENDING);
        earnings.put(earning.id(), earning);
        return new SettlementResult(earning, capture, released);
    }

    private SettlementResult priorSettlement(String key, String fingerprint) {
        if (key == null || key.isBlank()) throw error("IDEMPOTENCY_KEY_REQUIRED", "Settlement key required");
        SettlementOp prior = settlementIdempotency.get(key);
        if (prior == null) return null;
        if (!prior.fingerprint.equals(fingerprint)) throw error("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", "Settlement key reused with different payload");
        return prior.result;
    }

    private void rememberSettlement(String key, String fingerprint, SettlementResult result) {
        settlementIdempotency.put(key, new SettlementOp(fingerprint, result));
    }

    private Earning requiredEarning(UUID id) {
        Earning earning = earnings.get(id);
        if (earning == null) throw error("EARNING_NOT_FOUND", "Earning does not exist");
        return earning;
    }

    private Account providerClearing() {
        return ledger.account(OwnerType.PROVIDER_CLEARING, null, AccountType.ESCROW);
    }

    private Account platformRevenue() {
        return ledger.account(OwnerType.PLATFORM, null, AccountType.REVENUE);
    }

    private Account platformDispute() {
        return ledger.account(OwnerType.PLATFORM, null, AccountType.DISPUTE);
    }

    private Account userPending(UUID userId) {
        return ledger.account(OwnerType.USER, userId, AccountType.PENDING);
    }

    private Account userAvailable(UUID userId) {
        return ledger.account(OwnerType.USER, userId, AccountType.AVAILABLE);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw error("FINANCE_AMOUNT_INVALID", "Amount must be > 0");
    }

    private static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    private record SettlementOp(String fingerprint, SettlementResult result) {}
    private record PayoutOp(String fingerprint, PayoutTransfer transfer) {}
}

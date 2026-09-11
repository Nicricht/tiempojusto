package cl.tiempojusto.finance.ledger;

import cl.tiempojusto.finance.common.FinanceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InMemoryLedger {
    public enum Direction { DEBIT, CREDIT }
    public enum OwnerType { USER, PLATFORM, PROVIDER_CLEARING }
    public enum AccountType { AVAILABLE, PENDING, ESCROW, REVENUE, REFUND, DISPUTE }
    public enum TransactionType { CAPTURE, SESSION_SETTLEMENT, TICKET_SETTLEMENT, HOLD_RELEASE, REFUND, PAYOUT, PENALTY, COMPENSATION, CHARGEBACK }

    public record Account(UUID id, OwnerType ownerType, UUID ownerUserId, AccountType accountType, String currency) {}
    public record Posting(Account account, Direction direction, long amountClp) {
        public Posting {
            if (account == null || direction == null) throw new IllegalArgumentException("account/direction required");
            if (amountClp <= 0) throw new IllegalArgumentException("amountClp must be > 0");
        }
    }
    public record Entry(UUID id, UUID transactionId, Account account, Direction direction, long amountClp, Instant createdAt) {}
    public record Transaction(UUID id, TransactionType type, String referenceType, UUID referenceId,
                              String idempotencyKey, Instant postedAt, List<Entry> entries) {
        public Transaction { entries = List.copyOf(entries); }
    }

    private final Map<AccountKey, Account> accounts = new HashMap<>();
    private final Map<UUID, Transaction> transactions = new HashMap<>();
    private final Map<String, IdempotentTx> idempotency = new HashMap<>();

    public synchronized Account account(OwnerType ownerType, UUID ownerUserId, AccountType accountType) {
        if (ownerType == OwnerType.USER && ownerUserId == null) throw error("LEDGER_OWNER_REQUIRED", "USER account requires ownerUserId");
        if (ownerType != OwnerType.USER && ownerUserId != null) throw error("LEDGER_OWNER_FORBIDDEN", "Non-USER account must not have ownerUserId");
        AccountKey key = new AccountKey(ownerType, ownerUserId, accountType);
        return accounts.computeIfAbsent(key, ignored -> new Account(UUID.randomUUID(), ownerType, ownerUserId, accountType, "CLP"));
    }

    public synchronized Transaction post(TransactionType type, String referenceType, UUID referenceId,
                                         String idempotencyKey, Instant now, List<Posting> postings) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw error("IDEMPOTENCY_KEY_REQUIRED", "Ledger idempotency key required");
        if (postings == null || postings.size() < 2) throw error("LEDGER_MIN_ENTRIES", "Posted transaction needs at least two entries");
        long debits = postings.stream().filter(p -> p.direction() == Direction.DEBIT).mapToLong(Posting::amountClp).sum();
        long credits = postings.stream().filter(p -> p.direction() == Direction.CREDIT).mapToLong(Posting::amountClp).sum();
        if (debits <= 0 || debits != credits) throw error("LEDGER_UNBALANCED", "Debits must equal credits and be > 0");

        String fingerprint = fingerprint(type, referenceType, referenceId, postings);
        IdempotentTx prior = idempotency.get(idempotencyKey);
        if (prior != null) {
            if (!prior.fingerprint.equals(fingerprint)) throw error("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", "Ledger key reused with different payload");
            return prior.transaction;
        }

        UUID txId = UUID.randomUUID();
        List<Entry> entries = new ArrayList<>(postings.size());
        for (Posting posting : postings) {
            entries.add(new Entry(UUID.randomUUID(), txId, posting.account(), posting.direction(), posting.amountClp(), now));
        }
        Transaction tx = new Transaction(txId, type, Objects.requireNonNull(referenceType), referenceId, idempotencyKey, now, entries);
        transactions.put(txId, tx);
        idempotency.put(idempotencyKey, new IdempotentTx(fingerprint, tx));
        return tx;
    }

    public synchronized Transaction compensate(UUID originalTransactionId, String referenceType, UUID referenceId,
                                               String idempotencyKey, Instant now) {
        Transaction original = transaction(originalTransactionId);
        List<Posting> reversed = original.entries().stream()
                .map(e -> new Posting(e.account(), e.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT, e.amountClp()))
                .toList();
        return post(TransactionType.COMPENSATION, referenceType, referenceId, idempotencyKey, now, reversed);
    }

    public synchronized Transaction transaction(UUID id) {
        Transaction tx = transactions.get(id);
        if (tx == null) throw error("LEDGER_TRANSACTION_NOT_FOUND", "Ledger transaction not found");
        return tx;
    }

    public synchronized long balanceClp(Account account) {
        long debits = 0, credits = 0;
        for (Transaction tx : transactions.values()) {
            for (Entry e : tx.entries()) {
                if (!e.account().id().equals(account.id())) continue;
                if (e.direction() == Direction.DEBIT) debits += e.amountClp(); else credits += e.amountClp();
            }
        }
        return account.ownerType() == OwnerType.PROVIDER_CLEARING ? debits - credits : credits - debits;
    }

    public synchronized int transactionCount() { return transactions.size(); }

    private static String fingerprint(TransactionType type, String referenceType, UUID referenceId, List<Posting> postings) {
        StringBuilder b = new StringBuilder(type.name()).append('|').append(referenceType).append('|').append(referenceId);
        for (Posting p : postings) b.append('|').append(p.account().id()).append(':').append(p.direction()).append(':').append(p.amountClp());
        return b.toString();
    }

    private static FinanceException error(String code, String message) { return new FinanceException(code, message); }
    private record AccountKey(OwnerType ownerType, UUID ownerUserId, AccountType accountType) {}
    private record IdempotentTx(String fingerprint, Transaction transaction) {}
}

package cl.tiempojusto.app.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PersistentLedgerService {
    private final JdbcTemplate jdbc;

    public PersistentLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID post(String transactionType,
                     String referenceType,
                     UUID referenceId,
                     String idempotencyKey,
                     Instant now,
                     List<Posting> postings) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("ledger idempotency key is required");
        }
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("double-entry transaction requires at least two postings");
        }

        long debit = 0;
        long credit = 0;
        for (Posting posting : postings) {
            if (posting.amountClp() <= 0) throw new IllegalArgumentException("ledger posting amount must be positive");
            if ("DEBIT".equals(posting.direction())) debit = Math.addExact(debit, posting.amountClp());
            else if ("CREDIT".equals(posting.direction())) credit = Math.addExact(credit, posting.amountClp());
            else throw new IllegalArgumentException("ledger direction must be DEBIT or CREDIT");
        }
        if (debit != credit || debit == 0) {
            throw new IllegalArgumentException("ledger transaction must balance before persistence");
        }

        var existing = jdbc.query("""
                select id, transaction_type::text, reference_type, reference_id, status::text
                  from finance.ledger_transaction
                 where idempotency_key = ?
                """, (rs, n) -> new ExistingTransaction(
                rs.getObject("id", UUID.class), rs.getString("transaction_type"),
                rs.getString("reference_type"), rs.getObject("reference_id", UUID.class),
                rs.getString("status")), idempotencyKey);
        if (!existing.isEmpty()) {
            ExistingTransaction prior = existing.getFirst();
            boolean same = transactionType.equals(prior.transactionType())
                    && referenceType.equals(prior.referenceType())
                    && java.util.Objects.equals(referenceId, prior.referenceId());
            if (!same) throw new IllegalStateException("ledger idempotency key reused with different payload");
            if (!"POSTED".equals(prior.status())) {
                throw new IllegalStateException("existing idempotent ledger transaction is not POSTED");
            }
            return prior.id();
        }

        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
                insert into finance.ledger_transaction(
                    id, transaction_type, reference_type, reference_id, status,
                    idempotency_key, created_at)
                values (?, cast(? as platform.ledger_tx_type), ?, ?, 'PENDING', ?, ?)
                """, transactionId, transactionType, referenceType, referenceId,
                idempotencyKey, Timestamp.from(now));

        for (Posting posting : postings) {
            UUID accountId = account(posting.ownerType(), posting.ownerUserId(), posting.accountType());
            jdbc.update("""
                    insert into finance.ledger_entry(transaction_id, account_id, direction, amount_clp, created_at)
                    values (?, ?, cast(? as platform.entry_direction), ?, ?)
                    """, transactionId, accountId, posting.direction(), posting.amountClp(), Timestamp.from(now));
        }

        jdbc.update("""
                update finance.ledger_transaction
                   set status = 'POSTED', posted_at = ?
                 where id = ?
                """, Timestamp.from(now), transactionId);
        return transactionId;
    }

    @Transactional
    public UUID account(String ownerType, UUID ownerUserId, String accountType) {
        jdbc.update("""
                insert into finance.ledger_account(owner_type, owner_user_id, account_type, currency)
                values (cast(? as platform.ledger_owner_type), ?, cast(? as platform.ledger_account_type), 'CLP')
                on conflict do nothing
                """, ownerType, ownerUserId, accountType);
        var ids = jdbc.query("""
                select id from finance.ledger_account
                 where owner_type::text = ?
                   and owner_user_id is not distinct from ?
                   and account_type::text = ?
                   and currency = 'CLP'
                 order by created_at, id
                 limit 1
                """, (rs, n) -> rs.getObject(1, UUID.class), ownerType, ownerUserId, accountType);
        if (ids.isEmpty()) throw new IllegalStateException("ledger account could not be resolved");
        return ids.getFirst();
    }

    @Transactional(readOnly = true)
    public long userBalance(UUID userId, String accountType) {
        Long value = jdbc.queryForObject("""
                select coalesce(sum(case e.direction when 'CREDIT' then e.amount_clp else -e.amount_clp end), 0)
                  from finance.ledger_account a
                  left join finance.ledger_entry e on e.account_id = a.id
                  left join finance.ledger_transaction t on t.id = e.transaction_id and t.status = 'POSTED'
                 where a.owner_type = 'USER'
                   and a.owner_user_id = ?
                   and a.account_type::text = ?
                   and (e.id is null or t.id is not null)
                """, Long.class, userId, accountType);
        return value == null ? 0L : value;
    }

    public record Posting(String ownerType, UUID ownerUserId, String accountType,
                          String direction, long amountClp) {}

    private record ExistingTransaction(UUID id, String transactionType, String referenceType,
                                       UUID referenceId, String status) {}
}

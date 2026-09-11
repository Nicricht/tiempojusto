package cl.tiempojusto.app.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FinanceReadService {
    private final JdbcTemplate jdbc;
    private final PersistentLedgerService ledger;

    public FinanceReadService(JdbcTemplate jdbc, PersistentLedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public BalanceView balance(UUID userId) {
        long pendingTotal = ledger.userBalance(userId, "PENDING");
        long available = ledger.userBalance(userId, "AVAILABLE");
        Long heldValue = jdbc.queryForObject("""
                select coalesce(sum(p.net_amount_clp), 0)
                  from finance.payout p
                 where p.host_user_id = ?
                   and p.status = 'PENDING_HOLD'
                   and exists (
                       select 1 from finance.payout_hold h
                        where h.payout_id = p.id and h.status = 'ACTIVE'
                   )
                """, Long.class, userId);
        Long paidValue = jdbc.queryForObject("""
                select coalesce(sum(net_amount_clp), 0)
                  from finance.payout
                 where host_user_id = ? and status = 'SENT'
                """, Long.class, userId);
        long held = heldValue == null ? 0L : heldValue;
        long pending = Math.max(0L, pendingTotal - held);
        long paidOut = paidValue == null ? 0L : paidValue;
        return new BalanceView(pending, available, held, paidOut);
    }

    public record BalanceView(long pendingClp, long availableClp,
                              long heldForReviewClp, long paidOutClp) {}
}

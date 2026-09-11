package cl.tiempojusto.app.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PayoutAvailabilityApplicationService {
    private final JdbcTemplate jdbc;
    private final PersistentLedgerService ledger;
    private final ApplicationPersistenceSupport persistence;

    public PayoutAvailabilityApplicationService(JdbcTemplate jdbc,
                                                PersistentLedgerService ledger,
                                                ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.persistence = persistence;
    }

    @Transactional(readOnly = true)
    public List<UUID> duePayouts(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                select p.id
                  from finance.payout p
                 where p.status = 'PENDING_HOLD'
                   and p.pending_until <= clock_timestamp()
                   and not exists (
                       select 1 from finance.payout_hold h
                        where h.payout_id = p.id and h.status = 'ACTIVE'
                   )
                 order by p.pending_until, p.id
                 limit ?
                """, (rs, n) -> rs.getObject(1, UUID.class), safeLimit);
    }

    @Transactional
    public PayoutView releaseIfEligible(UUID payoutId) {
        PayoutRow payout = lockPayout(payoutId);
        if ("AVAILABLE".equals(payout.status())) return get(payoutId).withReplayed(true);
        if (!"PENDING_HOLD".equals(payout.status())) {
            throw new IllegalStateException("only PENDING_HOLD payout can become AVAILABLE");
        }
        Instant now = Instant.now();
        if (now.isBefore(payout.pendingUntil())) return get(payoutId);
        if (hasActiveHold(payoutId)) return get(payoutId);

        UUID releaseTransactionId = ledger.post(
                "HOLD_RELEASE", "PAYOUT", payoutId,
                "payout-hold-release:" + payoutId, now,
                List.of(
                        new PersistentLedgerService.Posting(
                                "USER", payout.hostUserId(), "PENDING", "DEBIT", payout.netAmountClp()),
                        new PersistentLedgerService.Posting(
                                "USER", payout.hostUserId(), "AVAILABLE", "CREDIT", payout.netAmountClp())
                ));

        jdbc.update("""
                update finance.payout
                   set status = 'AVAILABLE',
                       available_at = ?,
                       availability_transaction_id = ?
                 where id = ?
                """, Timestamp.from(now), releaseTransactionId, payoutId);

        persistence.audit(null, "PAYOUT_AVAILABLE", "PAYOUT", payoutId,
                Map.of("hostUserId", payout.hostUserId().toString(),
                        "netAmountClp", payout.netAmountClp(),
                        "holdMinutes", 60));
        persistence.outbox("PAYOUT", payoutId, "PAYOUT_AVAILABLE",
                Map.of("payoutId", payoutId.toString(),
                        "hostUserId", payout.hostUserId().toString(),
                        "amountClp", payout.netAmountClp()));
        return get(payoutId);
    }

    @Transactional
    public HoldView addObjectiveHold(UUID payoutId,
                                     UUID safetyCaseId,
                                     String reasonCode,
                                     String evidenceRef) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("objective hold reason is required");
        }
        if (evidenceRef == null || evidenceRef.isBlank()) {
            throw new IllegalArgumentException("objective hold evidence reference is required");
        }
        PayoutRow payout = lockPayout(payoutId);
        if (!"PENDING_HOLD".equals(payout.status())) {
            throw new IllegalStateException("objective incident can only hold a pending payout");
        }

        HoldView prior = activeHold(payoutId);
        if (prior != null) return prior.withReplayed(true);

        UUID holdId = UUID.randomUUID();
        jdbc.update("""
                insert into finance.payout_hold(
                    id, payout_id, safety_case_id, reason_code, evidence_ref, status)
                values (?, ?, ?, ?, ?, 'ACTIVE')
                """, holdId, payoutId, safetyCaseId, reasonCode, evidenceRef);
        persistence.audit(null, "PAYOUT_HELD_FOR_OBJECTIVE_INCIDENT", "PAYOUT", payoutId,
                Map.of("holdId", holdId.toString(), "reasonCode", reasonCode,
                        "evidenceRef", evidenceRef));
        return hold(holdId, false);
    }

    @Transactional
    public HoldView releaseObjectiveHold(UUID holdId) {
        HoldView current = hold(holdId, false);
        if ("RELEASED".equals(current.status())) return current.withReplayed(true);
        Instant now = Instant.now();
        jdbc.update("""
                update finance.payout_hold
                   set status = 'RELEASED', released_at = ?
                 where id = ? and status = 'ACTIVE'
                """, Timestamp.from(now), holdId);
        persistence.audit(null, "PAYOUT_OBJECTIVE_HOLD_RELEASED", "PAYOUT", current.payoutId(),
                Map.of("holdId", holdId.toString()));
        return hold(holdId, false);
    }

    @Transactional(readOnly = true)
    public PayoutView get(UUID payoutId) {
        var rows = jdbc.query("""
                select p.id, p.host_user_id, p.source_transaction_id,
                       p.gross_amount_clp, p.platform_fee_clp, p.net_amount_clp,
                       p.pending_until, p.status::text, p.available_at,
                       p.availability_transaction_id,
                       exists(select 1 from finance.payout_hold h
                              where h.payout_id = p.id and h.status = 'ACTIVE') active_hold
                  from finance.payout p
                 where p.id = ?
                """, (rs, n) -> new PayoutView(
                rs.getObject("id", UUID.class), rs.getObject("host_user_id", UUID.class),
                rs.getObject("source_transaction_id", UUID.class), rs.getLong("gross_amount_clp"),
                rs.getLong("platform_fee_clp"), rs.getLong("net_amount_clp"),
                rs.getTimestamp("pending_until").toInstant(), rs.getString("status"),
                instant(rs.getTimestamp("available_at")),
                rs.getObject("availability_transaction_id", UUID.class),
                rs.getBoolean("active_hold"), false), payoutId);
        if (rows.isEmpty()) throw new IllegalStateException("payout not found");
        return rows.getFirst();
    }

    private PayoutRow lockPayout(UUID payoutId) {
        var rows = jdbc.query("""
                select id, host_user_id, net_amount_clp, pending_until, status::text
                  from finance.payout
                 where id = ?
                 for update
                """, (rs, n) -> new PayoutRow(
                rs.getObject("id", UUID.class), rs.getObject("host_user_id", UUID.class),
                rs.getLong("net_amount_clp"), rs.getTimestamp("pending_until").toInstant(),
                rs.getString("status")), payoutId);
        if (rows.isEmpty()) throw new IllegalStateException("payout not found");
        return rows.getFirst();
    }

    private boolean hasActiveHold(UUID payoutId) {
        Boolean value = jdbc.queryForObject("""
                select exists(select 1 from finance.payout_hold
                               where payout_id = ? and status = 'ACTIVE')
                """, Boolean.class, payoutId);
        return Boolean.TRUE.equals(value);
    }

    private HoldView activeHold(UUID payoutId) {
        var rows = jdbc.query("""
                select id, payout_id, safety_case_id, reason_code, evidence_ref,
                       status, created_at, released_at
                  from finance.payout_hold
                 where payout_id = ? and status = 'ACTIVE'
                 order by created_at desc limit 1
                """, (rs, n) -> holdView(rs, false), payoutId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private HoldView hold(UUID holdId, boolean replayed) {
        var rows = jdbc.query("""
                select id, payout_id, safety_case_id, reason_code, evidence_ref,
                       status, created_at, released_at
                  from finance.payout_hold where id = ?
                """, (rs, n) -> holdView(rs, replayed), holdId);
        if (rows.isEmpty()) throw new IllegalStateException("payout hold not found");
        return rows.getFirst();
    }

    private static HoldView holdView(java.sql.ResultSet rs, boolean replayed) throws java.sql.SQLException {
        return new HoldView(
                rs.getObject("id", UUID.class), rs.getObject("payout_id", UUID.class),
                rs.getObject("safety_case_id", UUID.class), rs.getString("reason_code"),
                rs.getString("evidence_ref"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("released_at")), replayed);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record PayoutRow(UUID id, UUID hostUserId, long netAmountClp,
                             Instant pendingUntil, String status) {}

    public record PayoutView(UUID id, UUID hostUserId, UUID sourceTransactionId,
                             long grossAmountClp, long platformFeeClp, long netAmountClp,
                             Instant pendingUntil, String status, Instant availableAt,
                             UUID availabilityTransactionId, boolean activeObjectiveHold,
                             boolean replayed) {
        PayoutView withReplayed(boolean value) {
            return new PayoutView(id, hostUserId, sourceTransactionId, grossAmountClp,
                    platformFeeClp, netAmountClp, pendingUntil, status, availableAt,
                    availabilityTransactionId, activeObjectiveHold, value);
        }
    }

    public record HoldView(UUID id, UUID payoutId, UUID safetyCaseId,
                           String reasonCode, String evidenceRef, String status,
                           Instant createdAt, Instant releasedAt, boolean replayed) {
        HoldView withReplayed(boolean value) {
            return new HoldView(id, payoutId, safetyCaseId, reasonCode, evidenceRef,
                    status, createdAt, releasedAt, value);
        }
    }
}

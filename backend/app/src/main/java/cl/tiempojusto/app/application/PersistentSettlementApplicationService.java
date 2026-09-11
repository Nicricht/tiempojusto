package cl.tiempojusto.app.application;

import cl.tiempojusto.finance.payment.PaymentPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PersistentSettlementApplicationService {
    public static final int HOST_PERCENT = 80;
    public static final int PLATFORM_PERCENT = 20;

    private final JdbcTemplate jdbc;
    private final PaymentPort payments;
    private final PersistentLedgerService ledger;
    private final ApplicationPersistenceSupport persistence;

    public PersistentSettlementApplicationService(JdbcTemplate jdbc,
                                                  PaymentPort payments,
                                                  PersistentLedgerService ledger,
                                                  ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.ledger = ledger;
        this.persistence = persistence;
    }

    @Transactional(readOnly = true)
    public List<UUID> unsettledFinishedSessions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                select s.id
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                 where s.status = 'ENDED'
                   and a.modality = 'ONLINE'
                   and not exists (
                       select 1 from finance.session_settlement fs where fs.session_id = s.id
                   )
                 order by s.ended_at nulls last, s.id
                 limit ?
                """, (rs, n) -> rs.getObject(1, UUID.class), safeLimit);
    }

    @Transactional
    public SettlementView processSession(UUID sessionId) {
        SettlementContext ctx = lockContext(sessionId);
        SettlementView prior = existing(sessionId);
        if (prior != null) return prior.withReplayed(true);
        if (!"ENDED".equals(ctx.sessionStatus())) {
            throw new IllegalStateException("session must be ENDED before settlement");
        }

        int fullSeconds = Math.multiplyExact(ctx.durationMinutes(), 60);
        int billable = Math.min(ctx.billableSeconds(), fullSeconds);
        Instant now = Instant.now();

        if (billable == 0) {
            payments.releaseReservation(ctx.fundsReservationId(), providerKey(sessionId, "release-zero"), now);
            jdbc.update("""
                    update auction.funds_reservation
                       set status = 'RELEASED',
                           captured_amount_clp = 0,
                           released_amount_clp = amount_clp,
                           finalized_at = ?
                     where id = ?
                    """, Timestamp.from(now), ctx.fundsReservationId());
            jdbc.update("""
                    insert into finance.session_settlement(
                        session_id, appointment_id, host_user_id, bidder_user_id,
                        funds_reservation_id, status, agreed_amount_clp,
                        billable_seconds, full_duration_seconds, generated_amount_clp,
                        platform_fee_clp, host_net_clp, settled_at)
                    values (?, ?, ?, ?, ?, 'ZERO_BILLING', ?, 0, ?, 0, 0, 0, ?)
                    """, sessionId, ctx.appointmentId(), ctx.hostUserId(), ctx.bidderUserId(),
                    ctx.fundsReservationId(), ctx.agreedAmountClp(), fullSeconds, Timestamp.from(now));
            persistence.audit(null, "SESSION_SETTLED_ZERO_BILLING", "SESSION", sessionId,
                    Map.of("billableSeconds", 0, "releasedClp", ctx.reservedAmountClp()));
            persistence.outbox("SESSION", sessionId, "SESSION_SETTLED",
                    Map.of("sessionId", sessionId.toString(), "status", "ZERO_BILLING", "generatedAmountClp", 0));
            return existing(sessionId);
        }

        long proportionalNumerator = Math.multiplyExact(ctx.agreedAmountClp(), (long) billable);
        if (proportionalNumerator % fullSeconds != 0) {
            return persistRoundingBlock(ctx, fullSeconds, billable, null,
                    "PROPORTIONAL_CLP_FRACTION", now);
        }

        long generated = proportionalNumerator / fullSeconds;
        long hostNumerator = Math.multiplyExact(generated, (long) HOST_PERCENT);
        if (hostNumerator % 100L != 0) {
            return persistRoundingBlock(ctx, fullSeconds, billable, generated,
                    "SPLIT_80_20_CLP_FRACTION", now);
        }

        long hostAmount = hostNumerator / 100L;
        long platformAmount = Math.subtractExact(generated, hostAmount);
        if (generated <= 0 || hostAmount <= 0 || platformAmount <= 0) {
            throw new IllegalStateException("positive billable settlement produced non-positive finance posting");
        }
        if (generated > ctx.reservedAmountClp()) {
            throw new IllegalStateException("generated amount exceeds persisted funds reservation");
        }

        PaymentPort.Capture capture = payments.capture(
                ctx.fundsReservationId(), generated, providerKey(sessionId, "capture"), now);
        payments.releaseReservation(ctx.fundsReservationId(), providerKey(sessionId, "release-unused"), now);

        long releasedAmount = Math.subtractExact(ctx.reservedAmountClp(), generated);
        jdbc.update("""
                update auction.funds_reservation
                   set status = 'CAPTURED',
                       captured_amount_clp = ?,
                       released_amount_clp = ?,
                       finalized_at = ?
                 where id = ?
                """, generated, releasedAmount, Timestamp.from(now), ctx.fundsReservationId());

        UUID ledgerTransactionId = ledger.post(
                "SESSION_SETTLEMENT", "SESSION", sessionId,
                "session-settlement:" + sessionId, now,
                List.of(
                        new PersistentLedgerService.Posting(
                                "PROVIDER_CLEARING", null, "ESCROW", "DEBIT", generated),
                        new PersistentLedgerService.Posting(
                                "USER", ctx.hostUserId(), "PENDING", "CREDIT", hostAmount),
                        new PersistentLedgerService.Posting(
                                "PLATFORM", null, "REVENUE", "CREDIT", platformAmount)
                ));

        UUID payoutId = UUID.randomUUID();
        Instant pendingUntil = now.plusSeconds(60L * 60L);
        jdbc.update("""
                insert into finance.payout(
                    id, host_user_id, source_transaction_id, gross_amount_clp,
                    platform_fee_clp, net_amount_clp, pending_until, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING_HOLD', ?)
                """, payoutId, ctx.hostUserId(), ledgerTransactionId, generated,
                platformAmount, hostAmount, Timestamp.from(pendingUntil), Timestamp.from(now));

        jdbc.update("""
                insert into finance.session_settlement(
                    session_id, appointment_id, host_user_id, bidder_user_id,
                    funds_reservation_id, ledger_transaction_id, provider_capture_reference,
                    status, agreed_amount_clp, billable_seconds, full_duration_seconds,
                    generated_amount_clp, platform_fee_clp, host_net_clp, settled_at)
                values (?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, ctx.appointmentId(), ctx.hostUserId(), ctx.bidderUserId(),
                ctx.fundsReservationId(), ledgerTransactionId, capture.id().toString(),
                ctx.agreedAmountClp(), billable, fullSeconds, generated,
                platformAmount, hostAmount, Timestamp.from(now));

        persistence.audit(null, "SESSION_SETTLEMENT_POSTED", "SESSION", sessionId,
                Map.of("generatedAmountClp", generated,
                        "hostAmountClp", hostAmount,
                        "platformAmountClp", platformAmount,
                        "billableSeconds", billable,
                        "payoutId", payoutId.toString()));
        persistence.outbox("SESSION", sessionId, "SESSION_SETTLED",
                Map.of("sessionId", sessionId.toString(),
                        "status", "POSTED",
                        "generatedAmountClp", generated,
                        "hostAmountClp", hostAmount,
                        "platformAmountClp", platformAmount));

        return existing(sessionId);
    }

    @Transactional(readOnly = true)
    public SettlementView get(UUID sessionId) {
        SettlementView value = existing(sessionId);
        if (value == null) throw new IllegalStateException("settlement not found");
        return value;
    }

    private SettlementView persistRoundingBlock(SettlementContext ctx,
                                                int fullSeconds,
                                                int billable,
                                                Long generated,
                                                String reason,
                                                Instant now) {
        jdbc.update("""
                insert into finance.session_settlement(
                    session_id, appointment_id, host_user_id, bidder_user_id,
                    funds_reservation_id, status, agreed_amount_clp,
                    billable_seconds, full_duration_seconds, generated_amount_clp,
                    rounding_reason, created_at)
                values (?, ?, ?, ?, ?, 'PENDING_ROUNDING_POLICY', ?, ?, ?, ?, ?, ?)
                """, ctx.sessionId(), ctx.appointmentId(), ctx.hostUserId(), ctx.bidderUserId(),
                ctx.fundsReservationId(), ctx.agreedAmountClp(), billable, fullSeconds,
                generated, reason, Timestamp.from(now));
        persistence.audit(null, "SESSION_SETTLEMENT_BLOCKED_ROUNDING_POLICY", "SESSION", ctx.sessionId(),
                Map.of("billableSeconds", billable, "reason", reason));
        persistence.outbox("SESSION", ctx.sessionId(), "SESSION_SETTLEMENT_BLOCKED",
                Map.of("sessionId", ctx.sessionId().toString(), "reason", reason));
        return existing(ctx.sessionId());
    }

    private SettlementContext lockContext(UUID sessionId) {
        var rows = jdbc.query("""
                select s.id session_id, s.status::text session_status, s.billable_seconds,
                       a.id appointment_id, a.host_user_id, a.bidder_user_id,
                       a.duration_minutes, a.agreed_amount_clp,
                       fr.id funds_reservation_id, fr.amount_clp reserved_amount_clp
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  join auction.auction au on au.id = a.auction_id
                  join auction.bid b on b.id = au.winning_bid_id
                  join auction.funds_reservation fr on fr.id = b.funds_reservation_id
                 where s.id = ?
                 for update of s, fr
                """, (rs, n) -> new SettlementContext(
                rs.getObject("session_id", UUID.class), rs.getString("session_status"),
                rs.getInt("billable_seconds"), rs.getObject("appointment_id", UUID.class),
                rs.getObject("host_user_id", UUID.class), rs.getObject("bidder_user_id", UUID.class),
                rs.getInt("duration_minutes"), rs.getLong("agreed_amount_clp"),
                rs.getObject("funds_reservation_id", UUID.class), rs.getLong("reserved_amount_clp")), sessionId);
        if (rows.isEmpty()) throw new IllegalStateException("settlement context not found for session");
        return rows.getFirst();
    }

    private SettlementView existing(UUID sessionId) {
        var rows = jdbc.query("""
                select fs.session_id, fs.appointment_id, fs.status,
                       fs.billable_seconds, fs.full_duration_seconds,
                       fs.generated_amount_clp, fs.host_net_clp, fs.platform_fee_clp,
                       fs.rounding_reason, fs.ledger_transaction_id, fs.settled_at,
                       p.id payout_id, p.pending_until, p.status::text payout_status
                  from finance.session_settlement fs
                  left join finance.payout p on p.source_transaction_id = fs.ledger_transaction_id
                 where fs.session_id = ?
                """, (rs, n) -> new SettlementView(
                rs.getObject("session_id", UUID.class), rs.getObject("appointment_id", UUID.class),
                rs.getString("status"), rs.getInt("billable_seconds"), rs.getInt("full_duration_seconds"),
                (Long) rs.getObject("generated_amount_clp"), (Long) rs.getObject("host_net_clp"),
                (Long) rs.getObject("platform_fee_clp"), rs.getString("rounding_reason"),
                rs.getObject("ledger_transaction_id", UUID.class), rs.getObject("payout_id", UUID.class),
                instant(rs.getTimestamp("pending_until")), rs.getString("payout_status"),
                instant(rs.getTimestamp("settled_at")), false), sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String providerKey(UUID sessionId, String operation) {
        return "persistent-session:" + sessionId + ":" + operation;
    }

    private record SettlementContext(UUID sessionId, String sessionStatus, int billableSeconds,
                                     UUID appointmentId, UUID hostUserId, UUID bidderUserId,
                                     int durationMinutes, long agreedAmountClp,
                                     UUID fundsReservationId, long reservedAmountClp) {}

    public record SettlementView(UUID sessionId, UUID appointmentId, String status,
                                 int billableSeconds, int fullDurationSeconds,
                                 Long generatedAmountClp, Long hostAmountClp,
                                 Long platformAmountClp, String roundingReason,
                                 UUID ledgerTransactionId, UUID payoutId,
                                 Instant pendingUntil, String payoutStatus,
                                 Instant settledAt, boolean replayed) {
        SettlementView withReplayed(boolean value) {
            return new SettlementView(sessionId, appointmentId, status, billableSeconds,
                    fullDurationSeconds, generatedAmountClp, hostAmountClp, platformAmountClp,
                    roundingReason, ledgerTransactionId, payoutId, pendingUntil,
                    payoutStatus, settledAt, value);
        }
    }
}

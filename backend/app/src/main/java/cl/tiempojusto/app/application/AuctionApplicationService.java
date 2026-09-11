package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.finance.payment.PaymentPort.Reservation;
import cl.tiempojusto.finance.settlement.FinanceEngine;
import cl.tiempojusto.statemachine.auction.Auction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuctionApplicationService {
    private final JdbcTemplate jdbc;
    private final FinanceEngine finance;
    private final ApplicationPersistenceSupport persistence;

    public AuctionApplicationService(JdbcTemplate jdbc, FinanceEngine finance,
                                     ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.finance = finance;
        this.persistence = persistence;
    }

    @Transactional
    public AuctionView openSandbox(UUID hostActorId, OpenRequest request) {
        if (request == null || request.hostProfileId() == null) {
            throw ApiProblem.badRequest("AUCTION_REQUEST_INVALID", "hostProfileId requerido.");
        }
        UUID hostUserId = requireOwnedEligibleHost(hostActorId, request.hostProfileId());
        Instant now = Instant.now();
        Auction machine;
        try {
            machine = Auction.open(now, request.openingAmountClp(), request.closeNowAmountClp());
        } catch (RuntimeException ex) {
            throw ApiProblem.badRequest("AUCTION_OPEN_REJECTED", ex.getMessage());
        }
        if (!(request.durationMinutes() == 15 || request.durationMinutes() == 30 || request.durationMinutes() == 60)) {
            throw ApiProblem.badRequest("ONLINE_DURATION_INVALID", "Online admite 15, 30 o 60 minutos.");
        }

        Long next = jdbc.queryForObject("select platform.next_minimum_bid(?)", Long.class, request.openingAmountClp());
        UUID auctionId = UUID.randomUUID();
        jdbc.update("""
                insert into auction.auction(
                    id, host_user_id, modality, duration_minutes, opening_amount_clp,
                    current_amount_clp, next_actionable_amount_clp, instant_close_amount_clp,
                    status, started_at, scheduled_end_at, effective_end_at)
                values (?, ?, 'ONLINE', ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, auctionId, hostUserId, request.durationMinutes(), request.openingAmountClp(),
                request.openingAmountClp(), next, request.closeNowAmountClp(),
                Timestamp.from(machine.startedAt()), Timestamp.from(machine.deadline()), Timestamp.from(machine.deadline()));

        persistence.audit(hostActorId, "AUCTION_OPENED_SANDBOX", "AUCTION", auctionId,
                Map.of("hostProfileId", request.hostProfileId().toString(),
                        "openingAmountClp", request.openingAmountClp(),
                        "closeNowAmountClp", request.closeNowAmountClp()));
        persistence.outbox("AUCTION", auctionId, "AUCTION_STARTED",
                Map.of("auctionId", auctionId.toString(), "hostUserId", hostUserId.toString(),
                        "scheduledEndAt", machine.deadline().toString()));
        return get(auctionId);
    }

    @Transactional
    public CloseNowResult closeNow(UUID bidderActorId, UUID auctionId, String idempotencyKey) {
        requireEligibleBidder(bidderActorId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiProblem.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key requerido para Close Now.");
        }

        AuctionRow row = lockAuction(auctionId);
        if (!"OPEN".equals(row.status())) throw ApiProblem.conflict("AUCTION_NOT_OPEN", "Auction no está abierta.");
        if (row.hostUserId().equals(bidderActorId)) throw ApiProblem.forbidden("SELF_BID_FORBIDDEN", "HOST no puede pujar en su propia Auction.");
        if (row.closeNowAmountClp() == null) throw ApiProblem.conflict("CLOSE_NOW_DISABLED", "Close Now no está configurado.");

        String existingBidId = jdbc.query("select id::text from auction.bid where idempotency_key = ?",
                rs -> rs.next() ? rs.getString(1) : null, idempotencyKey);
        if (existingBidId != null) {
            UUID appointmentId = jdbc.query("""
                    select ap.id from appointment.appointment ap
                    join auction.auction a on a.id = ap.auction_id
                    where a.id = ? and ap.bidder_user_id = ?
                    """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, auctionId, bidderActorId);
            return new CloseNowResult(get(auctionId), appointmentId, UUID.fromString(existingBidId), true);
        }

        Instant now = Instant.now();
        Reservation reservation = finance.reserveBid(
                bidderActorId, row.closeNowAmountClp(), "api:close-now:" + idempotencyKey, now);
        jdbc.update("""
                insert into auction.funds_reservation(
                    id, user_id, provider_code, provider_reference, amount_clp,
                    status, purpose_type, purpose_id, reserved_at)
                values (?, ?, 'MOCK', ?, ?, 'RESERVED', 'AUCTION_CLOSE_NOW', ?, ?)
                """, reservation.id(), bidderActorId, "mock:" + reservation.id(),
                reservation.authorizedAmountClp(), auctionId, Timestamp.from(now));

        jdbc.update("""
                insert into auction.auction_participant(
                    auction_id, bidder_user_id, eligibility_status, joined_at)
                values (?, ?, 'ELIGIBLE', ?)
                on conflict (auction_id, bidder_user_id) do update
                    set eligibility_status = 'ELIGIBLE', last_active_at = excluded.joined_at
                """, auctionId, bidderActorId, Timestamp.from(now));

        UUID bidId = UUID.randomUUID();
        jdbc.update("""
                insert into auction.bid(
                    id, auction_id, bidder_user_id, amount_clp, bid_type, status,
                    funds_reservation_id, server_sequence, idempotency_key)
                values (?, ?, ?, ?, 'INSTANT_CLOSE', 'VALID', ?, 0, ?)
                """, bidId, auctionId, bidderActorId, row.closeNowAmountClp(), reservation.id(), idempotencyKey);

        AuctionView closed = get(auctionId);
        if (!"CLOSED".equals(closed.status()) || !bidderActorId.equals(closed.winnerUserId())) {
            throw new IllegalStateException("Database auction close trigger did not establish winner");
        }

        UUID appointmentId = UUID.randomUUID();
        jdbc.update("""
                insert into appointment.appointment(
                    id, auction_id, host_user_id, bidder_user_id, modality,
                    duration_minutes, agreed_amount_clp, status)
                values (?, ?, ?, ?, 'ONLINE', ?, ?, 'AWAITING_CONFIRMATION')
                """, appointmentId, auctionId, row.hostUserId(), bidderActorId,
                row.durationMinutes(), row.closeNowAmountClp());
        jdbc.update("""
                update auction.auction_participant
                   set rank_at_close = 1,
                       confirmation_deadline = ?,
                       confirmation_status = 'PENDING',
                       last_active_at = ?
                 where auction_id = ? and bidder_user_id = ?
                """, Timestamp.from(now.plusSeconds(180)), Timestamp.from(now), auctionId, bidderActorId);

        persistence.audit(bidderActorId, "AUCTION_CLOSE_NOW", "AUCTION", auctionId,
                Map.of("bidId", bidId.toString(), "appointmentId", appointmentId.toString(),
                        "amountClp", row.closeNowAmountClp()));
        persistence.outbox("AUCTION", auctionId, "CLOSE_NOW_EXECUTED",
                Map.of("auctionId", auctionId.toString(), "winnerUserId", bidderActorId.toString(),
                        "bidId", bidId.toString()));
        persistence.outbox("APPOINTMENT", appointmentId, "WINNER_SELECTED",
                Map.of("appointmentId", appointmentId.toString(), "bidderUserId", bidderActorId.toString(),
                        "confirmationDeadline", now.plusSeconds(180).toString()));

        return new CloseNowResult(closed, appointmentId, bidId, false);
    }

    @Transactional(readOnly = true)
    public AuctionView get(UUID auctionId) {
        var rows = jdbc.query("""
                select id, host_user_id, modality::text, duration_minutes, opening_amount_clp,
                       current_amount_clp, next_actionable_amount_clp, instant_close_amount_clp,
                       status::text, started_at, effective_end_at, winner_user_id, winning_bid_id, lock_version
                  from auction.auction where id = ?
                """, (rs, n) -> new AuctionView(
                rs.getObject("id", UUID.class), rs.getObject("host_user_id", UUID.class), rs.getString("modality"),
                rs.getInt("duration_minutes"), rs.getLong("opening_amount_clp"), rs.getLong("current_amount_clp"),
                rs.getLong("next_actionable_amount_clp"), (Long) rs.getObject("instant_close_amount_clp"),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("effective_end_at").toInstant(), rs.getObject("winner_user_id", UUID.class),
                rs.getObject("winning_bid_id", UUID.class), rs.getInt("lock_version")), auctionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("AUCTION_NOT_FOUND", "Auction no existe.");
        return rows.getFirst();
    }

    private AuctionRow lockAuction(UUID auctionId) {
        var rows = jdbc.query("""
                select id, host_user_id, duration_minutes, instant_close_amount_clp, status::text
                  from auction.auction where id = ? for update
                """, (rs, n) -> new AuctionRow(rs.getObject("id", UUID.class), rs.getObject("host_user_id", UUID.class),
                rs.getInt("duration_minutes"), (Long) rs.getObject("instant_close_amount_clp"), rs.getString("status")), auctionId);
        if (rows.isEmpty()) throw ApiProblem.notFound("AUCTION_NOT_FOUND", "Auction no existe.");
        return rows.getFirst();
    }

    private UUID requireOwnedEligibleHost(UUID actorId, UUID hostProfileId) {
        var rows = jdbc.query("""
                select hp.user_id, hp.profile_status::text profile_status, hp.supports_online,
                       u.account_status::text account_status,
                       exists(select 1 from iam.identity_verification v
                              where v.user_id = u.id and v.status = 'VERIFIED' and v.verified_adult = true) verified
                  from profile.host_profile hp join iam.app_user u on u.id = hp.user_id
                 where hp.id = ?
                """, (rs, n) -> new HostRow(rs.getObject("user_id", UUID.class), rs.getString("profile_status"),
                rs.getBoolean("supports_online"), rs.getString("account_status"), rs.getBoolean("verified")), hostProfileId);
        if (rows.isEmpty()) throw ApiProblem.notFound("HOST_PROFILE_NOT_FOUND", "Perfil HOST no existe.");
        HostRow h = rows.getFirst();
        if (!h.userId().equals(actorId)) throw ApiProblem.forbidden("HOST_PROFILE_FORBIDDEN", "Perfil HOST pertenece a otro usuario.");
        if (!"ACTIVE".equals(h.profileStatus()) || !h.supportsOnline() || !"ACTIVE".equals(h.accountStatus()) || !h.verified()) {
            throw ApiProblem.forbidden("HOST_NOT_ELIGIBLE", "HOST debe estar ACTIVE, KYC verificado y habilitado Online.");
        }
        return h.userId();
    }

    private void requireEligibleBidder(UUID actorId) {
        var rows = jdbc.query("""
                select role::text, account_status::text,
                       exists(select 1 from iam.identity_verification v
                              where v.user_id = u.id and v.status = 'VERIFIED' and v.verified_adult = true)
                  from iam.app_user u where id = ?
                """, (rs, n) -> new BidderRow(rs.getString(1), rs.getString(2), rs.getBoolean(3)), actorId);
        if (rows.isEmpty()) throw ApiProblem.notFound("USER_NOT_FOUND", "BIDDER no existe.");
        BidderRow b = rows.getFirst();
        if (!"BIDDER".equals(b.role()) || !"ACTIVE".equals(b.accountStatus()) || !b.verified()) {
            throw ApiProblem.forbidden("BIDDER_NOT_ELIGIBLE", "BIDDER debe estar ACTIVE y KYC adulto verificado.");
        }
    }

    private record HostRow(UUID userId, String profileStatus, boolean supportsOnline, String accountStatus, boolean verified) {}
    private record BidderRow(String role, String accountStatus, boolean verified) {}
    private record AuctionRow(UUID id, UUID hostUserId, int durationMinutes, Long closeNowAmountClp, String status) {}

    public record OpenRequest(UUID hostProfileId, int durationMinutes, long openingAmountClp, Long closeNowAmountClp) {}
    public record CloseNowResult(AuctionView auction, UUID appointmentId, UUID bidId, boolean replayed) {}
    public record AuctionView(UUID id, UUID hostUserId, String modality, int durationMinutes,
                              long openingAmountClp, long currentAmountClp, long nextActionableAmountClp,
                              Long closeNowAmountClp, String status, Instant startedAt, Instant effectiveEndAt,
                              UUID winnerUserId, UUID winningBidId, int lockVersion) {}
}

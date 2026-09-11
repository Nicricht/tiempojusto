package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.statemachine.proposal.Proposal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ProposalApplicationService {
    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;

    public ProposalApplicationService(JdbcTemplate jdbc, ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.persistence = persistence;
    }

    @Transactional
    public ProposalView create(UUID actorUserId, UUID hostProfileId, CreateRequest request) {
        requireEligibleBidder(actorUserId);
        requireHostSupports(hostProfileId, request.modality());
        boolean duplicate = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from market.proposal
                     where bidder_user_id = ? and host_profile_id = ?
                       and modality = ?::platform.modality and duration_minutes = ?
                       and status = 'ACTIVE')
                """, Boolean.class, actorUserId, hostProfileId, normalizedModality(request.modality()), request.durationMinutes()));

        Instant now = Instant.now();
        Proposal machine;
        try {
            machine = Proposal.activate(request.amountClp(), now, true, duplicate);
        } catch (RuntimeException ex) {
            throw ApiProblem.conflict("PROPOSAL_REJECTED", ex.getMessage());
        }

        UUID proposalId = UUID.randomUUID();
        jdbc.update("""
                insert into market.proposal(
                    id, bidder_user_id, host_profile_id, modality, duration_minutes,
                    amount_clp, status, valid_until, created_at)
                values (?, ?, ?, ?::platform.modality, ?, ?, 'ACTIVE', ?, ?)
                """, proposalId, actorUserId, hostProfileId, normalizedModality(request.modality()),
                request.durationMinutes(), machine.amountClp(), Timestamp.from(machine.expiresAt()), Timestamp.from(now));

        persistence.audit(actorUserId, "PROPOSAL_CREATED", "PROPOSAL", proposalId,
                Map.of("hostProfileId", hostProfileId.toString(), "amountClp", machine.amountClp(),
                        "modality", normalizedModality(request.modality()), "durationMinutes", request.durationMinutes()));
        persistence.outbox("PROPOSAL", proposalId, "PROPOSAL_CREATED",
                Map.of("proposalId", proposalId.toString(), "bidderUserId", actorUserId.toString(),
                        "hostProfileId", hostProfileId.toString(), "amountClp", machine.amountClp()));

        return getForActor(actorUserId, proposalId);
    }

    @Transactional(readOnly = true)
    public ProposalView getForActor(UUID actorUserId, UUID proposalId) {
        var rows = jdbc.query("""
                select id, bidder_user_id, host_profile_id, modality::text, duration_minutes,
                       amount_clp, status::text, valid_until, withdrawn_at, cooldown_until, lock_version
                  from market.proposal
                 where id = ?
                """, (rs, rowNum) -> new ProposalView(
                rs.getObject("id", UUID.class),
                rs.getObject("bidder_user_id", UUID.class),
                rs.getObject("host_profile_id", UUID.class),
                rs.getString("modality"),
                rs.getInt("duration_minutes"),
                rs.getLong("amount_clp"),
                rs.getString("status"),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toInstant(),
                rs.getTimestamp("cooldown_until") == null ? null : rs.getTimestamp("cooldown_until").toInstant(),
                rs.getInt("lock_version")), proposalId);
        if (rows.isEmpty()) throw ApiProblem.notFound("PROPOSAL_NOT_FOUND", "Proposal no existe.");
        ProposalView view = rows.getFirst();
        if (!view.bidderUserId().equals(actorUserId)) {
            throw ApiProblem.forbidden("PROPOSAL_FORBIDDEN", "La Proposal pertenece a otro BIDDER.");
        }
        return view;
    }

    private void requireEligibleBidder(UUID userId) {
        var rows = jdbc.query("""
                select u.role::text role, u.account_status::text account_status,
                       exists(select 1 from iam.identity_verification v
                              where v.user_id = u.id and v.status = 'VERIFIED' and v.verified_adult = true) verified
                  from iam.app_user u where u.id = ?
                """, (rs, n) -> new UserEligibility(rs.getString("role"), rs.getString("account_status"), rs.getBoolean("verified")), userId);
        if (rows.isEmpty()) throw ApiProblem.notFound("USER_NOT_FOUND", "Actor no existe.");
        UserEligibility u = rows.getFirst();
        if (!"BIDDER".equals(u.role())) throw ApiProblem.forbidden("BIDDER_REQUIRED", "Solo BIDDER puede crear Proposal.");
        if (!"ACTIVE".equals(u.accountStatus()) || !u.verifiedAdult()) {
            throw ApiProblem.forbidden("ACCOUNT_NOT_ELIGIBLE", "BIDDER debe estar ACTIVE y KYC adulto verificado.");
        }
    }

    private void requireHostSupports(UUID hostProfileId, String modalityRaw) {
        String modality = normalizedModality(modalityRaw);
        var rows = jdbc.query("""
                select hp.profile_status::text status, hp.supports_online, hp.supports_in_person,
                       u.account_status::text account_status
                  from profile.host_profile hp join iam.app_user u on u.id = hp.user_id
                 where hp.id = ?
                """, (rs, n) -> new HostEligibility(rs.getString("status"), rs.getBoolean("supports_online"),
                rs.getBoolean("supports_in_person"), rs.getString("account_status")), hostProfileId);
        if (rows.isEmpty()) throw ApiProblem.notFound("HOST_PROFILE_NOT_FOUND", "Perfil HOST no existe.");
        HostEligibility h = rows.getFirst();
        boolean supports = "ONLINE".equals(modality) ? h.supportsOnline() : h.supportsInPerson();
        if (!"ACTIVE".equals(h.status()) || !"ACTIVE".equals(h.accountStatus()) || !supports) {
            throw ApiProblem.conflict("HOST_NOT_AVAILABLE_FOR_MODALITY", "HOST no está habilitada para esta modalidad.");
        }
    }

    private static String normalizedModality(String value) {
        if (value == null) throw ApiProblem.badRequest("MODALITY_REQUIRED", "Modalidad requerida.");
        String m = value.trim().toUpperCase();
        if (!(m.equals("ONLINE") || m.equals("IN_PERSON"))) {
            throw ApiProblem.badRequest("MODALITY_INVALID", "Modalidad debe ser ONLINE o IN_PERSON.");
        }
        return m;
    }

    private record UserEligibility(String role, String accountStatus, boolean verifiedAdult) {}
    private record HostEligibility(String status, boolean supportsOnline, boolean supportsInPerson, String accountStatus) {}

    public record CreateRequest(String modality, int durationMinutes, long amountClp) {}
    public record ProposalView(UUID id, UUID bidderUserId, UUID hostProfileId, String modality,
                               int durationMinutes, long amountClp, String status, Instant validUntil,
                               Instant withdrawnAt, Instant cooldownUntil, int lockVersion) {}
}

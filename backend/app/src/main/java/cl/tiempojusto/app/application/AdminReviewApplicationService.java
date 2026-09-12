package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminReviewApplicationService {
    private final JdbcTemplate jdbc;

    public AdminReviewApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> users(UUID adminId, String query) {
        requireAdmin(adminId);
        String normalized = query == null ? "" : query.trim();
        String wildcard = "%" + normalized + "%";
        return jdbc.queryForList("""
                select u.id,
                       u.public_id,
                       u.role::text as role,
                       u.account_status::text as account_status,
                       p.username::text as username,
                       p.display_name,
                       p.public_age,
                       u.created_at,
                       k.provider_code as kyc_provider,
                       k.status::text as kyc_status,
                       k.verified_adult,
                       k.legal_country_code,
                       k.verified_at,
                       k.expires_at
                  from iam.app_user u
                  left join iam.public_identity p on p.user_id = u.id
                  left join lateral (
                      select v.provider_code,
                             v.status,
                             v.verified_adult,
                             v.legal_country_code,
                             v.verified_at,
                             v.expires_at
                        from iam.identity_verification v
                       where v.user_id = u.id
                       order by v.created_at desc
                       limit 1
                  ) k on true
                 where (? = ''
                    or u.id::text = ?
                    or u.public_id ilike ?
                    or coalesce(p.username::text, '') ilike ?
                    or coalesce(p.display_name, '') ilike ?
                    or coalesce(u.email::text, '') ilike ?)
                 order by u.created_at desc
                 limit 100
                """, normalized, normalized, wildcard, wildcard, wildcard, wildcard);
    }

    public Map<String, Object> auctionAudit(UUID adminId, UUID auctionId) {
        requireAdmin(adminId);
        List<Map<String, Object>> auctions = jdbc.queryForList("""
                select a.id,
                       a.host_user_id,
                       a.modality::text as modality,
                       a.duration_minutes,
                       a.opening_amount_clp,
                       a.current_amount_clp,
                       a.next_actionable_amount_clp,
                       a.instant_close_amount_clp,
                       a.status::text as status,
                       a.started_at,
                       a.scheduled_end_at,
                       a.effective_end_at,
                       a.winner_user_id,
                       a.winning_bid_id,
                       a.close_reason,
                       a.lock_version
                  from auction.auction a
                 where a.id = ?
                """, auctionId);
        if (auctions.isEmpty()) throw ApiProblem.notFound("AUCTION_NOT_FOUND", "Auction no existe.");
        List<Map<String, Object>> bids = jdbc.queryForList("""
                select b.id,
                       b.bidder_user_id,
                       b.amount_clp,
                       b.bid_type::text as bid_type,
                       b.status::text as status,
                       b.funds_reservation_id,
                       b.server_sequence,
                       b.created_at
                  from auction.bid b
                 where b.auction_id = ?
                 order by b.server_sequence asc
                """, auctionId);
        return Map.of("auction", auctions.getFirst(), "bids", bids);
    }

    public Map<String, Object> sessionAudit(UUID adminId, UUID sessionId) {
        requireAdmin(adminId);
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                select s.id,
                       s.appointment_id,
                       a.auction_id,
                       a.host_user_id,
                       a.bidder_user_id,
                       a.modality::text as modality,
                       a.duration_minutes,
                       a.agreed_amount_clp,
                       a.status::text as appointment_status,
                       a.winner_confirmed_at,
                       s.status::text as session_status,
                       s.free_started_at,
                       s.free_ends_at,
                       s.paid_started_at,
                       s.ended_at,
                       s.billable_seconds,
                       vr.id as video_room_id,
                       vr.status::text as video_status,
                       vr.join_deadline,
                       vr.free_online_end,
                       vr.paid_acceptance_deadline,
                       vr.media_lost_at,
                       vr.reconnect_deadline,
                       vr.persistent_recording_enabled
                  from appointment.appointment_session s
                  join appointment.appointment a on a.id = s.appointment_id
                  left join media.video_room vr on vr.appointment_id = a.id
                 where s.id = ?
                """, sessionId);
        if (sessions.isEmpty()) throw ApiProblem.notFound("SESSION_NOT_FOUND", "Session no existe.");

        List<Map<String, Object>> segments = jdbc.queryForList("""
                select id,
                       segment_type::text as segment_type,
                       started_at,
                       ended_at,
                       billable,
                       billable_seconds
                  from appointment.session_segment
                 where session_id = ?
                 order by started_at asc, id asc
                """, sessionId);

        List<Map<String, Object>> participants = jdbc.queryForList("""
                select ps.user_id,
                       ps.participant_role,
                       ps.joined_at,
                       ps.left_at,
                       ps.camera_valid,
                       ps.media_flowing,
                       ps.audio_muted,
                       ps.last_heartbeat_at,
                       ps.last_valid_media_at,
                       ps.updated_at
                  from media.video_participant_state ps
                  join media.video_room vr on vr.id = ps.video_room_id
                  join appointment.appointment_session s on s.appointment_id = vr.appointment_id
                 where s.id = ?
                 order by ps.participant_role
                """, sessionId);

        List<Map<String, Object>> incidents = jdbc.queryForList("""
                select mi.id,
                       mi.incident_type,
                       mi.detected_at,
                       mi.last_valid_media_at,
                       mi.recovered_at,
                       mi.provider_attributable
                  from media.media_incident mi
                  join media.video_room vr on vr.id = mi.video_room_id
                  join appointment.appointment_session s on s.appointment_id = vr.appointment_id
                 where s.id = ?
                 order by mi.detected_at asc, mi.id asc
                """, sessionId);

        return Map.of(
                "session", sessions.getFirst(),
                "segments", segments,
                "participants", participants,
                "incidents", incidents
        );
    }

    public List<Map<String, Object>> queue(UUID adminId) {
        requireAdmin(adminId);
        return jdbc.queryForList("""
                select t.id as task_id,
                       t.safety_case_id,
                       t.task_type,
                       t.status::text as task_status,
                       t.assigned_admin_user_id,
                       t.created_at,
                       c.subject_user_id,
                       c.severity::text as severity,
                       c.decision_status::text as decision_status,
                       c.action_code
                  from safety.human_review_task t
                  join safety.safety_case c on c.id = t.safety_case_id
                 where t.status in ('QUEUED','IN_PROGRESS')
                 order by case when c.severity = 'S5' then 0 else 1 end,
                          t.created_at asc
                """);
    }

    @Transactional
    public Map<String, Object> claim(UUID adminId, UUID taskId) {
        requireAdmin(adminId);
        int updated = jdbc.update("""
                update safety.human_review_task
                   set status = 'IN_PROGRESS', assigned_admin_user_id = ?
                 where id = ? and status = 'QUEUED'
                """, adminId, taskId);
        if (updated != 1) {
            throw ApiProblem.conflict("REVIEW_TASK_NOT_CLAIMABLE", "La tarea ya fue tomada o resuelta.");
        }
        UUID caseId = jdbc.queryForObject("select safety_case_id from safety.human_review_task where id = ?", UUID.class, taskId);
        writeAdminAction(adminId, "CLAIM_REVIEW", "SAFETY_CASE", caseId, "HumanReviewQueue claim");
        writeAudit(adminId, "ADMIN_REVIEW_CLAIMED", "SAFETY_CASE", caseId);
        return task(taskId);
    }

    public Map<String, Object> caseDetail(UUID adminId, UUID caseId) {
        requireAdmin(adminId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id,
                       c.subject_user_id,
                       c.report_id,
                       c.severity::text as severity,
                       c.decision_status::text as decision_status,
                       c.action_code,
                       c.reversible,
                       c.decided_at,
                       c.appeal_deadline,
                       c.created_at,
                       r.appointment_id,
                       r.category,
                       r.description,
                       r.status::text as report_status
                  from safety.safety_case c
                  left join safety.report r on r.id = c.report_id
                 where c.id = ?
                """, caseId);
        if (rows.isEmpty()) throw ApiProblem.notFound("SAFETY_CASE_NOT_FOUND", "SafetyCase no existe.");
        return rows.getFirst();
    }

    public List<Map<String, Object>> evidenceTimeline(UUID adminId, UUID caseId) {
        requireAdmin(adminId);
        ensureCase(caseId);
        return jdbc.queryForList("""
                select 'EVIDENCE' as timeline_type,
                       e.id::text as ref,
                       e.evidence_type::text as event_type,
                       e.storage_ref as detail,
                       e.created_at
                  from safety.evidence_item e
                 where e.safety_case_id = ?
                union all
                select 'AUDIT' as timeline_type,
                       a.id::text as ref,
                       a.event_type,
                       coalesce(a.metadata::text, '') as detail,
                       a.created_at
                  from platform.audit_event a
                 where a.entity_type = 'SAFETY_CASE' and a.entity_id = ?
                 order by created_at asc
                """, caseId, caseId);
    }

    @Transactional
    public Map<String, Object> decide(UUID adminId, UUID caseId, DecisionRequest request) {
        requireAdmin(adminId);
        if (request == null || request.outcome() == null || request.reason() == null || request.reason().isBlank()) {
            throw ApiProblem.badRequest("ADMIN_DECISION_INVALID", "Outcome y reason son obligatorios.");
        }
        String outcome = request.outcome().trim().toUpperCase();
        if (!List.of("CONFIRMED", "UNDETERMINED", "DISMISSED").contains(outcome)) {
            throw ApiProblem.badRequest("ADMIN_DECISION_OUTCOME_INVALID", "Outcome debe ser CONFIRMED, UNDETERMINED o DISMISSED.");
        }
        Map<String, Object> current = caseDetail(adminId, caseId);
        String severity = String.valueOf(current.get("severity"));
        if (request.finalSeverity() != null && !request.finalSeverity().isBlank()) {
            severity = normalizeSeverity(request.finalSeverity());
        }

        UUID assigned = jdbc.query("""
                select assigned_admin_user_id
                  from safety.human_review_task
                 where safety_case_id = ? and status in ('QUEUED','IN_PROGRESS')
                 order by created_at desc limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, caseId);
        if (assigned != null && !assigned.equals(adminId)) {
            throw ApiProblem.conflict("REVIEW_ASSIGNED_TO_OTHER_ADMIN", "El caso está asignado a otro administrador.");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime deadline = outcome.equals("CONFIRMED") ? now.plusDays(7) : null;
        jdbc.update("""
                update safety.safety_case
                   set severity = ?::platform.safety_level,
                       decision_status = 'CLOSED',
                       action_code = ?,
                       decided_at = ?,
                       appeal_deadline = ?
                 where id = ?
                """, severity, outcome, now, deadline, caseId);
        jdbc.update("""
                update safety.human_review_task
                   set status = 'RESOLVED',
                       assigned_admin_user_id = coalesce(assigned_admin_user_id, ?),
                       resolved_at = ?
                 where safety_case_id = ? and status in ('QUEUED','IN_PROGRESS')
                """, adminId, now, caseId);
        writeAdminAction(adminId, "SAFETY_DECISION_" + outcome, "SAFETY_CASE", caseId, request.reason());
        writeAudit(adminId, "SAFETY_CASE_DECIDED_" + outcome, "SAFETY_CASE", caseId);
        return caseDetail(adminId, caseId);
    }

    public List<Map<String, Object>> appeals(UUID adminId) {
        requireAdmin(adminId);
        return jdbc.queryForList("""
                select a.id,
                       a.safety_case_id,
                       a.appellant_user_id,
                       a.reason_text,
                       a.status::text as status,
                       a.submitted_at,
                       a.resolved_at,
                       c.severity::text as severity,
                       c.action_code as original_outcome
                  from safety.appeal a
                  join safety.safety_case c on c.id = a.safety_case_id
                 order by case when a.status = 'OPEN' then 0 else 1 end, a.submitted_at asc
                """);
    }

    @Transactional
    public Map<String, Object> resolveAppeal(UUID adminId, UUID appealId, AppealResolutionRequest request) {
        requireAdmin(adminId);
        if (request == null || request.outcome() == null || request.reason() == null || request.reason().isBlank()) {
            throw ApiProblem.badRequest("APPEAL_RESOLUTION_INVALID", "Outcome y reason son obligatorios.");
        }
        String requested = request.outcome().trim().toUpperCase();
        String dbStatus = switch (requested) {
            case "MAINTAIN" -> "UPHELD";
            case "REDUCE" -> "REDUCED";
            case "REVOKE" -> "REVOKED";
            default -> throw ApiProblem.badRequest("APPEAL_OUTCOME_INVALID", "Outcome debe ser MAINTAIN, REDUCE o REVOKE.");
        };
        List<Map<String, Object>> rows = jdbc.queryForList("select safety_case_id, status::text as status from safety.appeal where id = ?", appealId);
        if (rows.isEmpty()) throw ApiProblem.notFound("APPEAL_NOT_FOUND", "Appeal no existe.");
        if (!"OPEN".equals(rows.getFirst().get("status"))) throw ApiProblem.conflict("APPEAL_ALREADY_RESOLVED", "Appeal ya fue resuelta.");
        UUID caseId = (UUID) rows.getFirst().get("safety_case_id");

        jdbc.update("update safety.appeal set status = ?::platform.appeal_status, resolved_at = now() where id = ?", dbStatus, appealId);
        writeAdminAction(adminId, "APPEAL_" + requested, "SAFETY_CASE", caseId, request.reason());
        writeAudit(adminId, "SAFETY_APPEAL_" + requested, "SAFETY_CASE", caseId);
        return jdbc.queryForMap("""
                select id, safety_case_id, appellant_user_id, reason_text,
                       status::text as status, submitted_at, resolved_at
                  from safety.appeal where id = ?
                """, appealId);
    }

    public List<Map<String, Object>> riskSignals(UUID adminId, UUID userId) {
        requireAdmin(adminId);
        if (userId == null) {
            return jdbc.queryForList("""
                    select id, user_id, signal_type, score, source_ref, created_at
                      from safety.risk_signal order by created_at desc limit 200
                    """);
        }
        return jdbc.queryForList("""
                select id, user_id, signal_type, score, source_ref, created_at
                  from safety.risk_signal where user_id = ? order by created_at desc limit 200
                """, userId);
    }

    public Map<String, Object> payoutAudit(UUID adminId, UUID payoutId) {
        requireAdmin(adminId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.id,
                       p.host_user_id,
                       p.gross_amount_clp,
                       p.platform_fee_clp,
                       p.net_amount_clp,
                       p.status::text as status,
                       p.pending_until,
                       p.available_at,
                       p.source_transaction_id,
                       p.availability_transaction_id,
                       p.provider_reference,
                       p.created_at,
                       exists(select 1 from finance.payout_hold h where h.payout_id = p.id and h.status = 'ACTIVE') as held_for_review
                  from finance.payout p
                 where p.id = ?
                """, payoutId);
        if (rows.isEmpty()) throw ApiProblem.notFound("PAYOUT_NOT_FOUND", "Payout no existe.");
        return rows.getFirst();
    }

    public List<Map<String, Object>> payoutHolds(UUID adminId, UUID payoutId) {
        payoutAudit(adminId, payoutId);
        return jdbc.queryForList("""
                select id,
                       payout_id,
                       safety_case_id,
                       reason_code,
                       evidence_ref,
                       status,
                       created_at,
                       released_at
                  from finance.payout_hold
                 where payout_id = ?
                 order by created_at desc
                """, payoutId);
    }

    @Transactional
    public Map<String, Object> createPayoutHold(UUID adminId, UUID payoutId, PayoutHoldRequest request) {
        requireAdmin(adminId);
        if (request == null || request.reasonCode() == null || request.reasonCode().isBlank()
                || request.evidenceRef() == null || request.evidenceRef().isBlank()) {
            throw ApiProblem.badRequest("PAYOUT_HOLD_INVALID", "reasonCode y evidenceRef son obligatorios.");
        }
        Map<String, Object> payout = payoutAudit(adminId, payoutId);
        if (!"PENDING_HOLD".equals(payout.get("status"))) {
            throw ApiProblem.conflict("PAYOUT_HOLD_STATUS_INVALID", "Solo se puede crear un hold objetivo mientras el payout está PENDING_HOLD.");
        }
        if (request.safetyCaseId() != null) ensureCase(request.safetyCaseId());

        String reasonCode = request.reasonCode().trim().toUpperCase();
        String evidenceRef = request.evidenceRef().trim();
        Map<String, Object> hold = jdbc.queryForMap("""
                insert into finance.payout_hold(payout_id, safety_case_id, reason_code, evidence_ref)
                values (?, ?, ?, ?)
                returning id, payout_id, safety_case_id, reason_code, evidence_ref, status, created_at, released_at
                """, payoutId, request.safetyCaseId(), reasonCode, evidenceRef);
        writeAdminAction(adminId, "CREATE_PAYOUT_HOLD", "PAYOUT", payoutId,
                "Objective hold " + reasonCode + " evidence=" + evidenceRef);
        writeAudit(adminId, "PAYOUT_HOLD_CREATED", "PAYOUT", payoutId);
        return hold;
    }

    @Transactional
    public Map<String, Object> releasePayoutHold(UUID adminId, UUID payoutId, UUID holdId, PayoutHoldReleaseRequest request) {
        requireAdmin(adminId);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw ApiProblem.badRequest("PAYOUT_HOLD_RELEASE_INVALID", "reason es obligatorio.");
        }
        int updated = jdbc.update("""
                update finance.payout_hold
                   set status = 'RELEASED', released_at = now()
                 where id = ? and payout_id = ? and status = 'ACTIVE'
                """, holdId, payoutId);
        if (updated != 1) {
            throw ApiProblem.conflict("PAYOUT_HOLD_NOT_RELEASABLE", "El hold no existe, no pertenece al payout o ya fue liberado.");
        }
        writeAdminAction(adminId, "RELEASE_PAYOUT_HOLD", "PAYOUT", payoutId, request.reason().trim());
        writeAudit(adminId, "PAYOUT_HOLD_RELEASED", "PAYOUT", payoutId);
        return jdbc.queryForMap("""
                select id, payout_id, safety_case_id, reason_code, evidence_ref, status, created_at, released_at
                  from finance.payout_hold
                 where id = ?
                """, holdId);
    }

    public List<Map<String, Object>> ledgerAudit(UUID adminId, UUID transactionId) {
        requireAdmin(adminId);
        return jdbc.queryForList("""
                select t.id as transaction_id,
                       t.transaction_type::text as tx_type,
                       t.status::text as transaction_status,
                       t.reference_type,
                       t.reference_id,
                       t.created_at,
                       t.posted_at,
                       e.id as entry_id,
                       e.account_id,
                       e.direction::text as direction,
                       e.amount_clp
                  from finance.ledger_transaction t
                  join finance.ledger_entry e on e.transaction_id = t.id
                 where t.id = ?
                 order by e.id
                """, transactionId);
    }

    public List<Map<String, Object>> auditLog(UUID adminId, UUID caseId) {
        requireAdmin(adminId);
        if (caseId == null) {
            return jdbc.queryForList("""
                    select id, actor_user_id, event_type, entity_type, entity_id, metadata, created_at
                      from platform.audit_event order by created_at desc limit 200
                    """);
        }
        return jdbc.queryForList("""
                select id, actor_user_id, event_type, entity_type, entity_id, metadata, created_at
                  from platform.audit_event
                 where entity_type = 'SAFETY_CASE' and entity_id = ?
                 order by created_at desc
                """, caseId);
    }

    public List<Map<String, Object>> adminActions(UUID adminId, UUID targetId) {
        requireAdmin(adminId);
        if (targetId == null) {
            return jdbc.queryForList("""
                    select id, admin_user_id, action_type, target_type, target_id, reason, created_at
                      from platform.admin_action
                     order by created_at desc
                     limit 200
                    """);
        }
        return jdbc.queryForList("""
                select id, admin_user_id, action_type, target_type, target_id, reason, created_at
                  from platform.admin_action
                 where target_id = ?
                 order by created_at desc
                """, targetId);
    }

    private Map<String, Object> task(UUID taskId) {
        return jdbc.queryForMap("""
                select id, safety_case_id, task_type, status::text as status,
                       assigned_admin_user_id, created_at, resolved_at
                  from safety.human_review_task where id = ?
                """, taskId);
    }

    private void ensureCase(UUID caseId) {
        Integer count = jdbc.queryForObject("select count(*) from safety.safety_case where id = ?", Integer.class, caseId);
        if (count == null || count == 0) throw ApiProblem.notFound("SAFETY_CASE_NOT_FOUND", "SafetyCase no existe.");
    }

    private void requireAdmin(UUID adminId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select role::text as role, account_status::text as status from iam.app_user where id = ?", adminId);
        if (rows.isEmpty()) throw ApiProblem.unauthorized("ADMIN_ACTOR_NOT_FOUND", "Actor no existe.");
        Map<String, Object> actor = rows.getFirst();
        if (!"ADMIN".equals(actor.get("role")) || !"ACTIVE".equals(actor.get("status"))) {
            throw ApiProblem.forbidden("ADMIN_REQUIRED", "Se requiere un ADMIN activo.");
        }
    }

    private String normalizeSeverity(String raw) {
        String value = raw.trim().toUpperCase();
        if (!List.of("S0", "S1", "S2", "S3", "S4", "S5").contains(value)) {
            throw ApiProblem.badRequest("SAFETY_LEVEL_INVALID", "Nivel Safety inválido.");
        }
        return value;
    }

    private void writeAdminAction(UUID adminId, String action, String targetType, UUID targetId, String reason) {
        jdbc.update("""
                insert into platform.admin_action(admin_user_id, action_type, target_type, target_id, reason)
                values (?, ?, ?, ?, ?)
                """, adminId, action, targetType, targetId, reason);
    }

    private void writeAudit(UUID actorId, String eventType, String entityType, UUID entityId) {
        jdbc.update("""
                insert into platform.audit_event(actor_user_id, event_type, entity_type, entity_id, metadata)
                values (?, ?, ?, ?, '{}'::jsonb)
                """, actorId, eventType, entityType, entityId);
    }

    public record DecisionRequest(String outcome, String finalSeverity, String reason) {}
    public record AppealResolutionRequest(String outcome, String reason) {}
    public record PayoutHoldRequest(String reasonCode, String evidenceRef, UUID safetyCaseId) {}
    public record PayoutHoldReleaseRequest(String reason) {}
}

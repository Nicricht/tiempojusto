package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminReviewApplicationService {
    private final JdbcTemplate jdbc;

    public AdminReviewApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

        Instant now = Instant.now();
        Instant deadline = outcome.equals("CONFIRMED") ? now.plusSeconds(7L * 24 * 3600) : null;
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
                       p.amount_clp,
                       p.status::text as status,
                       p.pending_until,
                       p.source_transaction_id,
                       p.provider_reference,
                       p.created_at,
                       p.updated_at,
                       exists(select 1 from finance.payout_hold h where h.payout_id = p.id and h.released_at is null) as held_for_review
                  from finance.payout p
                 where p.id = ?
                """, payoutId);
        if (rows.isEmpty()) throw ApiProblem.notFound("PAYOUT_NOT_FOUND", "Payout no existe.");
        return rows.getFirst();
    }

    public List<Map<String, Object>> ledgerAudit(UUID adminId, UUID transactionId) {
        requireAdmin(adminId);
        return jdbc.queryForList("""
                select t.id as transaction_id,
                       t.tx_type::text as tx_type,
                       t.status::text as transaction_status,
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
}

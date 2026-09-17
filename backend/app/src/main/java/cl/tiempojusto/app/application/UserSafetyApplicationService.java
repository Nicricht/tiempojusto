package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class UserSafetyApplicationService {
    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;

    public UserSafetyApplicationService(JdbcTemplate jdbc, ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.persistence = persistence;
    }

    @Transactional
    public ReportView createReport(UUID actor, ReportRequest request) {
        if (request == null || request.targetUserId() == null) {
            throw ApiProblem.badRequest("REPORT_TARGET_REQUIRED", "Usuario objetivo requerido.");
        }
        if (actor.equals(request.targetUserId())) {
            throw ApiProblem.badRequest("REPORT_SELF_INVALID", "La acción requiere otro usuario.");
        }
        requireUser(request.targetUserId());
        if (request.appointmentId() != null) {
            requireReportAppointmentPair(actor, request.targetUserId(), request.appointmentId());
        }
        String category = request.category() == null ? "" : request.category().trim();
        String description = request.description() == null ? "" : request.description().trim();
        if (category.isBlank() || category.length() > 50) {
            throw ApiProblem.badRequest("REPORT_CATEGORY_INVALID", "Categoría inválida.");
        }
        if (description.length() < 10 || description.length() > 4000) {
            throw ApiProblem.badRequest("REPORT_DESCRIPTION_INVALID", "Descripción inválida.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into safety.report(id, reporter_user_id, reported_user_id, appointment_id, category, description, status)
                values (?, ?, ?, ?, ?, ?, 'OPEN')
                """, id, actor, request.targetUserId(), request.appointmentId(), category, description);
        persistence.audit(actor, "SAFETY_REPORT_CREATED", "SAFETY_REPORT", id,
                Map.of("targetUserId", request.targetUserId().toString(), "reportIsNotGuilt", true));
        persistence.outbox("SAFETY_REPORT", id, "SAFETY_REPORT_CREATED",
                Map.of("reportId", id.toString(), "targetUserId", request.targetUserId().toString()));
        return new ReportView(id, request.targetUserId(), "OPEN");
    }

    @Transactional
    public BlockView setBlocked(UUID actor, UUID targetUserId, boolean blocked) {
        if (actor.equals(targetUserId)) {
            throw ApiProblem.badRequest("BLOCK_SELF_INVALID", "La acción requiere otro usuario.");
        }
        requireUser(targetUserId);
        if (blocked) {
            jdbc.update("""
                    insert into iam.user_block(blocker_user_id, blocked_user_id)
                    values (?, ?)
                    on conflict (blocker_user_id, blocked_user_id) do nothing
                    """, actor, targetUserId);
            persistence.audit(actor, "USER_BLOCKED", "USER", targetUserId, Map.of());
        } else {
            jdbc.update("delete from iam.user_block where blocker_user_id = ? and blocked_user_id = ?", actor, targetUserId);
            persistence.audit(actor, "USER_UNBLOCKED", "USER", targetUserId, Map.of());
        }
        return new BlockView(targetUserId, blocked);
    }

    @Transactional(readOnly = true)
    public BlockView getBlock(UUID actor, UUID targetUserId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from iam.user_block where blocker_user_id = ? and blocked_user_id = ?",
                Integer.class, actor, targetUserId);
        return new BlockView(targetUserId, count != null && count > 0);
    }

    private void requireUser(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from iam.app_user where id = ?", Integer.class, userId);
        if (count == null || count == 0) throw ApiProblem.notFound("USER_NOT_FOUND", "Usuario no existe.");
    }

    private void requireReportAppointmentPair(UUID actor, UUID targetUserId, UUID appointmentId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from appointment.appointment
                 where id = ?
                   and ((host_user_id = ? and bidder_user_id = ?)
                     or (host_user_id = ? and bidder_user_id = ?))
                """, Integer.class, appointmentId, actor, targetUserId, targetUserId, actor);
        if (count == null || count == 0) {
            throw ApiProblem.forbidden(
                    "REPORT_APPOINTMENT_FORBIDDEN",
                    "La cita no corresponde a las partes del reporte.");
        }
    }

    public record ReportRequest(UUID targetUserId, UUID appointmentId, String category, String description) {}
    public record ReportView(UUID id, UUID targetUserId, String status) {}
    public record BlockView(UUID userId, boolean blocked) {}
}

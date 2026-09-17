package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSafetyApplicationServiceTest {
    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ApplicationPersistenceSupport persistence;

    private UserSafetyApplicationService service;

    @BeforeEach
    void setUp() {
        service = new UserSafetyApplicationService(jdbc, persistence);
    }

    @Test
    void reportCannotAttachAppointmentOutsideReporterTargetPair() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID appointment = UUID.randomUUID();

        when(jdbc.queryForObject(
                "select count(*) from iam.app_user where id = ?",
                Integer.class,
                target)).thenReturn(1);
        when(jdbc.queryForObject(
                contains("from appointment.appointment"),
                eq(Integer.class),
                eq(appointment),
                eq(actor),
                eq(target),
                eq(target),
                eq(actor))).thenReturn(0);

        ApiProblem problem = assertThrows(ApiProblem.class, () -> service.createReport(
                actor,
                new UserSafetyApplicationService.ReportRequest(
                        target,
                        appointment,
                        "USER_REPORT",
                        "Descripción válida del incidente.")));

        assertEquals("REPORT_APPOINTMENT_FORBIDDEN", problem.code());
    }
}

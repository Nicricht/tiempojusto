package cl.tiempojusto.app.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseSchemaVerifierTest {

    @Test
    void probesCompleteDatabaseIdentityAfterSchemaVerification() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select postgis_version()", String.class)).thenReturn("3.5");
        when(jdbc.queryForObject(eq("select to_regclass(?)::text"), eq(String.class), any()))
                .thenReturn("present");
        when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("tiempojusto_staging");
        when(jdbc.queryForObject("select current_user", String.class)).thenReturn("tiempojusto_user");
        when(jdbc.queryForObject("select inet_server_addr()::text", String.class)).thenReturn("10.0.0.12");
        when(jdbc.queryForObject("select inet_server_port()", Integer.class)).thenReturn(5432);

        new DatabaseSchemaVerifier(jdbc).run(null);

        verify(jdbc).queryForObject("select current_database()", String.class);
        verify(jdbc).queryForObject("select current_user", String.class);
        verify(jdbc).queryForObject("select inet_server_addr()::text", String.class);
        verify(jdbc).queryForObject("select inet_server_port()", Integer.class);
    }
}

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
    void probesDatabaseNameAfterSchemaVerification() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select postgis_version()", String.class)).thenReturn("3.5");
        when(jdbc.queryForObject(eq("select to_regclass(?)::text"), eq(String.class), any()))
                .thenReturn("present");
        when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("tiempojusto_staging");

        new DatabaseSchemaVerifier(jdbc).run(null);

        verify(jdbc).queryForObject("select current_database()", String.class);
    }
}

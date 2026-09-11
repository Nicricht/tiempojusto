package cl.tiempojusto.app.runtime;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "tiempojusto.runtime.verify-database", havingValue = "true", matchIfMissing = true)
public class DatabaseSchemaVerifier implements ApplicationRunner {

    private static final List<String> REQUIRED_RELATIONS = List.of(
            "iam.app_user",
            "market.proposal",
            "auction.auction",
            "appointment.appointment_session",
            "finance.ledger_entry",
            "finance.payout",
            "geo.route_estimate_snapshot",
            "media.video_participant_state"
    );

    private final JdbcTemplate jdbc;

    public DatabaseSchemaVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        String postgis = jdbc.queryForObject("select postgis_version()", String.class);
        if (postgis == null || postgis.isBlank()) {
            throw new IllegalStateException("PostGIS is not available");
        }

        for (String relation : REQUIRED_RELATIONS) {
            String resolved = jdbc.queryForObject("select to_regclass(?)::text", String.class, relation);
            if (resolved == null) {
                throw new IllegalStateException("Required database relation is missing: " + relation);
            }
        }
    }
}

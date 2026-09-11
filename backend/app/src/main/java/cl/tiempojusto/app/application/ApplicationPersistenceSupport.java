package cl.tiempojusto.app.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class ApplicationPersistenceSupport {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApplicationPersistenceSupport(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void outbox(String aggregateType, UUID aggregateId, String eventType, Map<String, ?> payload) {
        jdbc.update("""
                insert into platform.outbox_event(aggregate_type, aggregate_id, event_type, payload)
                values (?, ?, ?, ?::jsonb)
                """, aggregateType, aggregateId, eventType, json(payload));
    }

    public void audit(UUID actorUserId, String eventType, String entityType, UUID entityId, Map<String, ?> metadata) {
        jdbc.update("""
                insert into platform.audit_event(actor_user_id, event_type, entity_type, entity_id, metadata)
                values (?, ?, ?, ?, ?::jsonb)
                """, actorUserId, eventType, entityType, entityId, json(metadata));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize persisted event payload", ex);
        }
    }
}

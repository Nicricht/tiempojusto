package cl.tiempojusto.app.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.temporal.TemporalAccessor;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ApplicationPersistenceSupport {
    private final JdbcTemplate jdbc;

    public ApplicationPersistenceSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    /**
     * Small dependency-free JSON encoder for persisted operational metadata.
     * Application payloads here are deliberately limited to primitives, UUID/temporal values,
     * maps and lists. This is not a general-purpose JSON parser.
     */
    private String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof UUID || value instanceof TemporalAccessor || value instanceof Enum<?>) {
            return quote(value.toString());
        }
        if (value instanceof CharSequence sequence) return quote(sequence.toString());
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> entry = it.next();
                out.append(quote(String.valueOf(entry.getKey())))
                   .append(':')
                   .append(json(entry.getValue()));
                if (it.hasNext()) out.append(',');
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("[");
            Iterator<?> it = iterable.iterator();
            while (it.hasNext()) {
                out.append(json(it.next()));
                if (it.hasNext()) out.append(',');
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray() && value instanceof Object[] array) {
            return json(List.of(array));
        }
        return quote(value.toString());
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}

package cl.tiempojusto.app.payment;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.app.payment.ProviderPaymentReconciliationEvaluator.Decision;
import cl.tiempojusto.app.payment.ProviderPaymentReconciliationEvaluator.LocalState;
import cl.tiempojusto.app.payment.ProviderPaymentReconciliationEvaluator.Result;
import cl.tiempojusto.finance.common.FinanceException;
import cl.tiempojusto.finance.payment.provider.MercadoPagoPaymentPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "tiempojusto.payment.provider", havingValue = "MERCADO_PAGO")
public class MercadoPagoWebhookReconciliationService {
    private static final String PROVIDER = MercadoPagoPaymentPort.PROVIDER;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MercadoPagoWebhookSignatureVerifier verifier;
    private final MercadoPagoPaymentQueryClient provider;
    private final int maxAttempts;

    public MercadoPagoWebhookReconciliationService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            MercadoPagoWebhookSignatureVerifier verifier,
            MercadoPagoPaymentQueryClient provider,
            @Value("${tiempojusto.payment.reconciliation.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.json = json;
        this.verifier = verifier;
        this.provider = provider;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public Acceptance accept(String xSignature, String xRequestId, String queryDataId,
                             String queryType, String rawBody) {
        var verified = verifier.verify(xSignature, xRequestId, queryDataId);
        if (verified.normalizedDataId() == null) {
            throw ApiProblem.badRequest("PAYMENT_WEBHOOK_DATA_ID_REQUIRED", "data.id requerido.");
        }
        if (rawBody == null || rawBody.isBlank()) {
            throw ApiProblem.badRequest("PAYMENT_WEBHOOK_BODY_REQUIRED", "Webhook body requerido.");
        }

        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (Exception ex) {
            throw ApiProblem.badRequest("PAYMENT_WEBHOOK_BODY_INVALID", "Webhook JSON inválido.");
        }

        String providerEventId = text(root, "id");
        if (providerEventId == null) {
            throw ApiProblem.badRequest("PAYMENT_WEBHOOK_EVENT_ID_REQUIRED", "Webhook id requerido.");
        }
        String bodyType = text(root, "type");
        String resourceType = firstNonBlank(queryType, bodyType, "unknown");
        String action = text(root, "action");
        Boolean liveMode = root.has("live_mode") && !root.get("live_mode").isNull()
                ? root.get("live_mode").asBoolean() : null;
        String payloadHash = sha256(rawBody);
        UUID eventId = UUID.randomUUID();

        int inserted = jdbc.update("""
                insert into finance.provider_webhook_event(
                    id, provider_code, provider_event_id, provider_resource_id,
                    resource_type, action, request_id, live_mode, signature_ts, payload_sha256)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (provider_code, provider_event_id) do nothing
                """, eventId, PROVIDER, providerEventId, verified.normalizedDataId(),
                resourceType, action, xRequestId, liveMode, verified.timestamp(), payloadHash);

        if (inserted == 0) {
            String status = jdbc.queryForObject("""
                    select processing_status from finance.provider_webhook_event
                     where provider_code = ? and provider_event_id = ?
                    """, String.class, PROVIDER, providerEventId);
            return new Acceptance(providerEventId, verified.normalizedDataId(), status, true);
        }

        jdbc.update("""
                insert into platform.audit_event(actor_user_id, event_type, entity_type, entity_id, metadata)
                values (null, 'PAYMENT_PROVIDER_WEBHOOK_RECEIVED', 'PROVIDER_PAYMENT', null,
                        jsonb_build_object('provider', ?, 'providerEventId', ?,
                                           'providerPaymentId', ?, 'action', coalesce(?, '')))
                """, PROVIDER, providerEventId, verified.normalizedDataId(), action);

        if (!"payment".equalsIgnoreCase(resourceType)) {
            jdbc.update("""
                    update finance.provider_webhook_event
                       set processing_status = 'IGNORED', processed_at = now()
                     where id = ?
                    """, eventId);
            return new Acceptance(providerEventId, verified.normalizedDataId(), "IGNORED", false);
        }
        return new Acceptance(providerEventId, verified.normalizedDataId(), "RECEIVED", false);
    }

    @Scheduled(fixedDelayString = "${tiempojusto.payment.reconciliation.poll-ms:5000}")
    public void reconcilePending() {
        for (PendingEvent event : claim(20)) {
            reconcile(event);
        }
    }

    public int reconcileNowForTests() {
        List<PendingEvent> events = claim(20);
        events.forEach(this::reconcile);
        return events.size();
    }

    private List<PendingEvent> claim(int limit) {
        return jdbc.query("""
                with picked as (
                    select id
                      from finance.provider_webhook_event
                     where processing_status in ('RECEIVED','PENDING_LOCAL_COMMIT')
                       and next_attempt_at <= now()
                     order by received_at
                     for update skip locked
                     limit ?
                )
                update finance.provider_webhook_event e
                   set processing_status = 'PROCESSING',
                       attempt_count = attempt_count + 1,
                       last_error_code = null,
                       last_error_detail = null
                  from picked
                 where e.id = picked.id
                returning e.id, e.provider_event_id, e.provider_resource_id, e.attempt_count
                """, (rs, row) -> new PendingEvent(
                rs.getObject("id", UUID.class),
                rs.getString("provider_event_id"),
                rs.getString("provider_resource_id"),
                rs.getInt("attempt_count")), limit);
    }

    private void reconcile(PendingEvent event) {
        try {
            MercadoPagoPaymentQueryClient.Snapshot snapshot = provider.getPayment(event.providerPaymentId());
            LocalState local = localState(event.providerPaymentId());
            Decision decision = ProviderPaymentReconciliationEvaluator.evaluate(snapshot, local);
            saveDecision(event, snapshot, local, decision);
        } catch (FinanceException ex) {
            retryOrFail(event, ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            retryOrFail(event, "PAYMENT_RECONCILIATION_UNEXPECTED", ex.getMessage());
        }
    }

    private LocalState localState(String providerPaymentId) {
        return jdbc.query("""
                select r.internal_id reservation_id,
                       r.authorized_amount_clp,
                       r.reservation_status,
                       c.internal_id capture_id,
                       c.amount_clp captured_amount_clp,
                       coalesce((select sum(rr.amount_clp)
                                   from finance.provider_refund_binding rr
                                  where rr.capture_id = c.internal_id), 0) refunded_amount_clp
                  from finance.provider_reservation_binding r
                  left join finance.provider_capture_binding c
                    on c.reservation_id = r.internal_id
                 where r.provider_code = ? and r.provider_payment_id = ?
                """, rs -> {
            if (!rs.next()) return null;
            UUID captureId = rs.getObject("capture_id", UUID.class);
            Long captured = captureId == null ? null : rs.getLong("captured_amount_clp");
            return new LocalState(
                    rs.getObject("reservation_id", UUID.class),
                    rs.getLong("authorized_amount_clp"),
                    rs.getString("reservation_status"),
                    captureId,
                    captured,
                    rs.getLong("refunded_amount_clp"));
        }, PROVIDER, providerPaymentId);
    }

    private void saveDecision(PendingEvent event,
                              MercadoPagoPaymentQueryClient.Snapshot snapshot,
                              LocalState local,
                              Decision decision) {
        jdbc.update("""
                insert into finance.provider_reconciliation(
                    webhook_event_id, provider_code, provider_payment_id,
                    internal_reservation_id, internal_capture_id,
                    provider_status, provider_amount_clp, provider_refunded_clp,
                    local_authorized_clp, local_captured_clp, local_refunded_clp,
                    result, reason, checked_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (webhook_event_id) do update set
                    internal_reservation_id = excluded.internal_reservation_id,
                    internal_capture_id = excluded.internal_capture_id,
                    provider_status = excluded.provider_status,
                    provider_amount_clp = excluded.provider_amount_clp,
                    provider_refunded_clp = excluded.provider_refunded_clp,
                    local_authorized_clp = excluded.local_authorized_clp,
                    local_captured_clp = excluded.local_captured_clp,
                    local_refunded_clp = excluded.local_refunded_clp,
                    result = excluded.result,
                    reason = excluded.reason,
                    checked_at = now()
                """, event.id(), PROVIDER, snapshot.providerPaymentId(),
                local == null ? null : local.reservationId(),
                local == null ? null : local.captureId(),
                snapshot.status(), snapshot.amountClp(), snapshot.refundedClp(),
                local == null ? null : local.localAuthorizedClp(),
                local == null ? null : local.localCapturedClp(),
                local == null ? null : local.localRefundedClp(),
                decision.result().name(), trim(decision.reason()));

        if (decision.result() == Result.PENDING_LOCAL_COMMIT && event.attemptCount() < maxAttempts) {
            long delaySeconds = Math.min(60, 5L * event.attemptCount());
            jdbc.update("""
                    update finance.provider_webhook_event
                       set processing_status = 'PENDING_LOCAL_COMMIT',
                           next_attempt_at = now() + (? * interval '1 second')
                     where id = ?
                    """, delaySeconds, event.id());
            return;
        }

        String finalStatus = decision.result() == Result.PENDING_LOCAL_COMMIT
                ? "MISMATCH" : decision.result().name();
        String finalReason = decision.result() == Result.PENDING_LOCAL_COMMIT
                ? "Local state did not converge after " + event.attemptCount() + " reconciliation attempts"
                : decision.reason();
        jdbc.update("""
                update finance.provider_webhook_event
                   set processing_status = ?, processed_at = now(),
                       last_error_detail = ?
                 where id = ?
                """, finalStatus, trim(finalReason), event.id());

        if ("MISMATCH".equals(finalStatus) || "UNBOUND".equals(finalStatus)) {
            auditAnomaly(event, finalStatus, finalReason);
        }
    }

    private void retryOrFail(PendingEvent event, String code, String detail) {
        if (event.attemptCount() >= maxAttempts) {
            jdbc.update("""
                    update finance.provider_webhook_event
                       set processing_status = 'FAILED', processed_at = now(),
                           last_error_code = ?, last_error_detail = ?
                     where id = ?
                    """, code, trim(detail), event.id());
            auditAnomaly(event, "FAILED", code + ": " + detail);
            return;
        }
        long delaySeconds = Math.min(120, 10L * event.attemptCount());
        jdbc.update("""
                update finance.provider_webhook_event
                   set processing_status = 'RECEIVED',
                       next_attempt_at = now() + (? * interval '1 second'),
                       last_error_code = ?, last_error_detail = ?
                 where id = ?
                """, delaySeconds, code, trim(detail), event.id());
    }

    private void auditAnomaly(PendingEvent event, String result, String reason) {
        jdbc.update("""
                insert into platform.audit_event(actor_user_id, event_type, entity_type, entity_id, metadata)
                values (null, 'PAYMENT_PROVIDER_RECONCILIATION_ANOMALY', 'PROVIDER_PAYMENT', null,
                        jsonb_build_object('provider', ?, 'providerEventId', ?,
                                           'providerPaymentId', ?, 'result', ?, 'reason', ?))
                """, PROVIDER, event.providerEventId(), event.providerPaymentId(), result, trim(reason));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record PendingEvent(UUID id, String providerEventId, String providerPaymentId, int attemptCount) {}
    public record Acceptance(String providerEventId, String providerPaymentId, String status, boolean duplicate) {}
}

package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.app.identity.VeriffIdentityVerificationAdapter.IdentityProviderException;
import cl.tiempojusto.identity.IdentityVerificationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Durable KYC webhook inbox and reconciliation worker.
 *
 * Webhook receipt is deliberately separated from provider decision polling so
 * the provider can receive a fast 200 acknowledgement. Only the SHA-256 of the
 * signed raw payload is persisted. Documents, selfies, biometrics and the raw
 * webhook body remain outside TiempoJusto storage.
 */
@Service
public class KycWebhookReconciliationService {
    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;
    private final List<IdentityVerificationPort> providers;
    private final TransactionTemplate tx;
    private final int maxAttempts;

    public KycWebhookReconciliationService(
            JdbcTemplate jdbc,
            ApplicationPersistenceSupport persistence,
            List<IdentityVerificationPort> providers,
            TransactionTemplate tx,
            @Value("${tiempojusto.kyc.reconciliation.max-attempts:12}") int maxAttempts) {
        this.jdbc = jdbc;
        this.persistence = persistence;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.tx = tx;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional
    public WebhookResult accept(String providerCode, String rawBody, Map<String, String> headers) {
        IdentityVerificationPort provider = requiredProvider(providerCode);
        if (!provider.verifyWebhook(rawBody, headers)) {
            throw ApiProblem.unauthorized("KYC_WEBHOOK_SIGNATURE_INVALID", "Firma de webhook KYC inválida.");
        }

        String providerReference = provider.providerReferenceFromWebhook(rawBody)
                .orElseThrow(() -> ApiProblem.badRequest(
                        "KYC_WEBHOOK_REFERENCE_MISSING", "Webhook KYC sin referencia de sesión."));
        String payloadHash = sha256(rawBody);
        UUID eventId = UUID.randomUUID();

        int inserted = jdbc.update("""
                insert into iam.identity_provider_event(
                    id, provider_code, provider_reference, payload_sha256,
                    event_type, received_at, processing_status, next_attempt_at)
                values (?, ?, ?, ?, 'VERIFICATION_WEBHOOK', clock_timestamp(), 'RECEIVED', clock_timestamp())
                on conflict (provider_code, payload_sha256) do nothing
                """, eventId, provider.providerCode(), providerReference, payloadHash);

        if (inserted == 0) {
            EventStatus existing = jdbc.query("""
                    select provider_reference, processing_status,
                           coalesce(normalized_status::text, '') normalized_status
                      from iam.identity_provider_event
                     where provider_code = ? and payload_sha256 = ?
                    """, rs -> rs.next()
                    ? new EventStatus(rs.getString("provider_reference"),
                                      rs.getString("processing_status"),
                                      rs.getString("normalized_status"))
                    : null, provider.providerCode(), payloadHash);
            if (existing == null) {
                throw ApiProblem.unavailable("KYC_WEBHOOK_IDEMPOTENCY_LOOKUP_FAILED",
                        "No fue posible recuperar el webhook KYC duplicado.");
            }
            String status = existing.normalizedStatus().isBlank()
                    ? existing.processingStatus() : existing.normalizedStatus();
            return new WebhookResult(provider.providerCode(), existing.providerReference(), status, false, true);
        }

        persistence.audit(null, "KYC_PROVIDER_WEBHOOK_RECEIVED", "IDENTITY_PROVIDER_EVENT", eventId,
                Map.of("provider", provider.providerCode(), "providerReference", providerReference));
        return new WebhookResult(provider.providerCode(), providerReference, "RECEIVED", false, false);
    }

    @Scheduled(fixedDelayString = "${tiempojusto.kyc.reconciliation.poll-ms:3000}")
    public void reconcilePending() {
        // Most developer/test runtimes intentionally have no external KYC
        // provider. In that mode do not touch provider-specific migration state.
        if (providers.isEmpty()) return;
        for (PendingEvent event : claim(20)) {
            reconcile(event);
        }
    }

    /** Visible to integration tests without exposing a product endpoint. */
    public int reconcileNowForTests() {
        if (providers.isEmpty()) return 0;
        List<PendingEvent> events = claim(20);
        events.forEach(this::reconcile);
        return events.size();
    }

    private List<PendingEvent> claim(int limit) {
        return jdbc.query("""
                with picked as (
                    select id
                      from iam.identity_provider_event
                     where processing_status in ('RECEIVED','PENDING_LOCAL_SESSION','PENDING_PROVIDER')
                       and next_attempt_at <= clock_timestamp()
                     order by received_at
                     for update skip locked
                     limit ?
                )
                update iam.identity_provider_event e
                   set processing_status = 'PROCESSING',
                       attempt_count = attempt_count + 1,
                       last_error_code = null,
                       last_error_detail = null
                  from picked
                 where e.id = picked.id
                returning e.id, e.provider_code, e.provider_reference, e.attempt_count
                """, (rs, row) -> new PendingEvent(
                rs.getObject("id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("provider_reference"),
                rs.getInt("attempt_count")), limit);
    }

    private void reconcile(PendingEvent event) {
        IdentityVerificationPort provider;
        try {
            provider = requiredProvider(event.providerCode());
        } catch (RuntimeException ex) {
            retryOrFail(event, "PENDING_PROVIDER", "KYC_PROVIDER_NOT_AVAILABLE", ex.getMessage());
            return;
        }

        LocalVerification local = findLocal(event.providerCode(), event.providerReference());
        if (local == null) {
            retryOrFail(event, "PENDING_LOCAL_SESSION", "KYC_LOCAL_SESSION_NOT_READY",
                    "Signed webhook arrived before the local verification session was visible");
            return;
        }

        IdentityVerificationPort.VerificationDecision decision;
        try {
            decision = provider.pollDecision(event.providerReference(), Instant.now()).orElse(null);
        } catch (IdentityProviderException ex) {
            retryOrFail(event, "PENDING_PROVIDER", ex.code(), ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            retryOrFail(event, "PENDING_PROVIDER", "KYC_PROVIDER_UNAVAILABLE", ex.getMessage());
            return;
        }

        if (decision == null || decision.status() == IdentityVerificationPort.DecisionStatus.PENDING) {
            retryOrFail(event, "PENDING_PROVIDER", "KYC_DECISION_PENDING",
                    "Provider has not produced a terminal/review decision yet");
            return;
        }

        tx.executeWithoutResult(status -> applyDecision(event, decision));
    }

    private LocalVerification findLocal(String providerCode, String providerReference) {
        return jdbc.query("""
                select id, user_id, status::text, verified_adult
                  from iam.identity_verification
                 where provider_code = ? and provider_reference = ?
                """, rs -> rs.next() ? new LocalVerification(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("verified_adult")) : null,
                providerCode, providerReference);
    }

    private void applyDecision(PendingEvent event, IdentityVerificationPort.VerificationDecision decision) {
        LocalVerification current = jdbc.query("""
                select id, user_id, status::text, verified_adult
                  from iam.identity_verification
                 where provider_code = ? and provider_reference = ?
                 for update
                """, rs -> rs.next() ? new LocalVerification(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("verified_adult")) : null,
                event.providerCode(), event.providerReference());

        if (current == null) {
            retryOrFail(event, "PENDING_LOCAL_SESSION", "KYC_LOCAL_SESSION_NOT_READY",
                    "Local verification disappeared before decision application");
            return;
        }

        String incoming = decision.status().name();
        if (isTerminal(current.status()) && !current.status().equals(incoming)) {
            markConflict(event, current, decision);
            return;
        }

        boolean sameState = current.status().equals(incoming)
                && current.verifiedAdult() == decision.verifiedAdult();
        if (!sameState) {
            boolean verified = decision.status() == IdentityVerificationPort.DecisionStatus.VERIFIED;
            java.sql.Timestamp verifiedAt = verified ? java.sql.Timestamp.from(decision.decidedAt()) : null;
            jdbc.update("""
                    update iam.identity_verification
                       set status = ?::platform.verification_status,
                           verified_adult = ?,
                           legal_country_code = ?,
                           date_of_birth = null,
                           verified_at = ?::timestamptz
                     where id = ?
                    """, incoming, decision.verifiedAdult(), decision.legalCountryCode(),
                    verifiedAt, current.id());

            jdbc.update("""
                    update iam.identity_provider_session
                       set normalized_status = ?::platform.verification_status,
                           last_provider_event_at = clock_timestamp(),
                           updated_at = clock_timestamp()
                     where verification_id = ?
                    """, incoming, current.id());

            if (verified && decision.identityVerified() && decision.verifiedAdult()) {
                jdbc.update("""
                        update iam.app_user
                           set account_status = 'ACTIVE'
                         where id = ? and account_status = 'PENDING_VERIFICATION'
                        """, current.userId());
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", event.providerCode());
            metadata.put("status", incoming);
            metadata.put("verifiedAdult", decision.verifiedAdult());
            if (decision.reasonCode() != null) metadata.put("reasonCode", decision.reasonCode());
            persistence.audit(current.userId(), "KYC_PROVIDER_DECISION_APPLIED",
                    "IDENTITY_VERIFICATION", current.id(), metadata);
            persistence.outbox("USER", current.userId(), "IDENTITY_VERIFICATION_UPDATED",
                    Map.of("userId", current.userId().toString(),
                            "verificationId", current.id().toString(),
                            "status", incoming,
                            "verifiedAdult", decision.verifiedAdult()));
        } else {
            jdbc.update("""
                    update iam.identity_provider_session
                       set last_provider_event_at = clock_timestamp(), updated_at = clock_timestamp()
                     where verification_id = ?
                    """, current.id());
        }

        jdbc.update("""
                update iam.identity_provider_event
                   set processing_status = 'APPLIED',
                       normalized_status = ?::platform.verification_status,
                       applied_at = clock_timestamp(),
                       last_error_code = null,
                       last_error_detail = null
                 where id = ?
                """, incoming, event.id());
    }

    private void markConflict(PendingEvent event, LocalVerification current,
                              IdentityVerificationPort.VerificationDecision decision) {
        String detail = "Terminal local KYC state " + current.status()
                + " conflicts with provider decision " + decision.status().name();
        jdbc.update("""
                update iam.identity_provider_event
                   set processing_status = 'CONFLICT',
                       normalized_status = ?::platform.verification_status,
                       last_error_code = 'KYC_TERMINAL_STATE_CONFLICT',
                       last_error_detail = ?
                 where id = ?
                """, decision.status().name(), trim(detail), event.id());
        persistence.audit(null, "KYC_PROVIDER_RECONCILIATION_ANOMALY", "IDENTITY_PROVIDER_EVENT", event.id(),
                Map.of("provider", event.providerCode(),
                        "providerReference", event.providerReference(),
                        "localStatus", current.status(),
                        "providerStatus", decision.status().name()));
    }

    private void retryOrFail(PendingEvent event, String pendingStatus, String code, String detail) {
        if (event.attemptCount() >= maxAttempts) {
            jdbc.update("""
                    update iam.identity_provider_event
                       set processing_status = 'FAILED',
                           last_error_code = ?, last_error_detail = ?
                     where id = ?
                    """, code, trim(detail), event.id());
            persistence.audit(null, "KYC_PROVIDER_RECONCILIATION_ANOMALY", "IDENTITY_PROVIDER_EVENT", event.id(),
                    Map.of("provider", event.providerCode(),
                            "providerReference", event.providerReference(),
                            "code", code,
                            "attemptCount", event.attemptCount()));
            return;
        }

        long delaySeconds = Math.min(120L, 5L * Math.max(1, event.attemptCount()));
        jdbc.update("""
                update iam.identity_provider_event
                   set processing_status = ?,
                       next_attempt_at = clock_timestamp() + (? * interval '1 second'),
                       last_error_code = ?, last_error_detail = ?
                 where id = ?
                """, pendingStatus, delaySeconds, code, trim(detail), event.id());
    }

    private IdentityVerificationPort requiredProvider(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return providers.stream()
                .filter(p -> p.providerCode().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> ApiProblem.unavailable(
                        "KYC_PROVIDER_NOT_AVAILABLE", "Proveedor KYC no disponible en runtime."));
    }

    private static boolean isTerminal(String status) {
        return "VERIFIED".equals(status) || "REJECTED".equals(status) || "EXPIRED".equals(status);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash KYC webhook", ex);
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record PendingEvent(UUID id, String providerCode, String providerReference, int attemptCount) {}
    private record LocalVerification(UUID id, UUID userId, String status, boolean verifiedAdult) {}
    private record EventStatus(String providerReference, String processingStatus, String normalizedStatus) {}

    public record WebhookResult(String provider, String providerReference, String status,
                                boolean accountEligibleForActivation, boolean duplicate) {}
}

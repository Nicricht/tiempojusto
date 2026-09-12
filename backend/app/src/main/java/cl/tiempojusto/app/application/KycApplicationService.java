package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import cl.tiempojusto.app.identity.VeriffIdentityVerificationAdapter.IdentityProviderException;
import cl.tiempojusto.identity.IdentityVerificationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KycApplicationService {
    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;
    private final List<IdentityVerificationPort> providers;
    private final String configuredProvider;
    private final String callbackUrl;

    public KycApplicationService(
            JdbcTemplate jdbc,
            ApplicationPersistenceSupport persistence,
            List<IdentityVerificationPort> providers,
            @Value("${tiempojusto.kyc.provider:}") String configuredProvider,
            @Value("${tiempojusto.kyc.callback-url:}") String callbackUrl) {
        this.jdbc = jdbc;
        this.persistence = persistence;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.configuredProvider = configuredProvider == null ? "" : configuredProvider.trim().toUpperCase(Locale.ROOT);
        this.callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
    }

    @Transactional
    public StartResult start(UUID userId) {
        if (userId == null) throw ApiProblem.unauthorized("ACTOR_REQUIRED", "Usuario autenticado requerido.");
        IdentityVerificationPort provider = requiredConfiguredProvider();
        if (!provider.capabilities().satisfiesTiempoJustoV1()) {
            throw ApiProblem.unavailable("KYC_CAPABILITIES_INCOMPLETE", "El proveedor KYC no demuestra las capacidades mínimas requeridas.");
        }
        if (callbackUrl.isBlank() || !callbackUrl.startsWith("https://")) {
            throw ApiProblem.unavailable("KYC_CALLBACK_NOT_CONFIGURED", "KYC requiere callback HTTPS configurado.");
        }

        List<UserRow> users = jdbc.query("""
                select id, account_status::text
                from iam.app_user where id = ?
                """, (rs, rowNum) -> new UserRow(
                rs.getObject("id", UUID.class), rs.getString("account_status")), userId);
        if (users.isEmpty()) throw ApiProblem.notFound("USER_NOT_FOUND", "Usuario no existe.");
        String accountStatus = users.getFirst().accountStatus();
        if ("CLOSED".equals(accountStatus)) {
            throw ApiProblem.forbidden("ACCOUNT_CLOSED", "La cuenta está cerrada.");
        }

        Integer alreadyVerified = jdbc.queryForObject("""
                select count(*) from iam.identity_verification
                where user_id = ? and status = 'VERIFIED' and verified_adult = true
                  and (expires_at is null or expires_at > clock_timestamp())
                """, Integer.class, userId);
        if (alreadyVerified != null && alreadyVerified > 0) {
            throw ApiProblem.conflict("KYC_ALREADY_VERIFIED", "La identidad adulta ya está verificada.");
        }

        Integer open = jdbc.queryForObject("""
                select count(*) from iam.identity_verification
                where user_id = ? and provider_code = ? and status in ('PENDING','REVIEW')
                """, Integer.class, userId, provider.providerCode());
        if (open != null && open > 0) {
            throw ApiProblem.conflict("KYC_ALREADY_IN_PROGRESS", "Ya existe una verificación KYC abierta con este proveedor.");
        }

        IdentityVerificationPort.VerificationSession session;
        try {
            session = provider.start(new IdentityVerificationPort.StartVerification(userId, callbackUrl, Instant.now()));
        } catch (IdentityProviderException ex) {
            throw ApiProblem.unavailable(ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            throw ApiProblem.unavailable("KYC_PROVIDER_UNAVAILABLE", "No fue posible iniciar la verificación KYC.");
        }

        UUID verificationId = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(session.createdAt());
        jdbc.update("""
                insert into iam.identity_verification(
                    id, user_id, provider_code, provider_reference, status, verified_adult, created_at)
                values (?, ?, ?, ?, 'PENDING', false, ?)
                """, verificationId, userId, provider.providerCode(), session.providerReference(), createdAt);

        jdbc.update("""
                insert into iam.identity_provider_session(
                    verification_id, provider_code, provider_reference, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """, verificationId, provider.providerCode(), session.providerReference(), createdAt, createdAt);

        persistence.audit(userId, "KYC_PROVIDER_SESSION_STARTED", "IDENTITY_VERIFICATION", verificationId,
                Map.of("provider", provider.providerCode()));
        persistence.outbox("USER", userId, "IDENTITY_VERIFICATION_STARTED",
                Map.of("userId", userId.toString(), "verificationId", verificationId.toString(), "provider", provider.providerCode()));

        return new StartResult(verificationId, provider.providerCode(), session.verificationUrl(), "PENDING");
    }

    @Transactional(readOnly = true)
    public Optional<StatusResult> latest(UUID userId) {
        List<StatusResult> results = jdbc.query("""
                select id, provider_code, status::text, verified_adult, legal_country_code,
                       verified_at, expires_at, created_at
                from iam.identity_verification
                where user_id = ?
                order by created_at desc
                limit 1
                """, (rs, rowNum) -> new StatusResult(
                rs.getObject("id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("status"),
                rs.getBoolean("verified_adult"),
                rs.getString("legal_country_code"),
                rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), userId);
        return results.stream().findFirst();
    }

    @Transactional
    public WebhookResult handleWebhook(String providerCode, String rawBody, Map<String, String> headers) {
        IdentityVerificationPort provider = requiredProvider(providerCode);
        if (!provider.verifyWebhook(rawBody, headers)) {
            throw ApiProblem.unauthorized("KYC_WEBHOOK_SIGNATURE_INVALID", "Firma de webhook KYC inválida.");
        }

        String providerReference = provider.providerReferenceFromWebhook(rawBody)
                .orElseThrow(() -> ApiProblem.badRequest("KYC_WEBHOOK_REFERENCE_MISSING", "Webhook KYC sin referencia de sesión."));

        String payloadHash = sha256(rawBody);
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into iam.identity_provider_event(
                    id, provider_code, provider_reference, payload_sha256, event_type, received_at)
                values (?, ?, ?, ?, 'VERIFICATION_WEBHOOK', clock_timestamp())
                on conflict (provider_code, payload_sha256) do nothing
                """, eventId, provider.providerCode(), providerReference, payloadHash);
        if (inserted == 0) {
            return new WebhookResult(provider.providerCode(), providerReference, "DUPLICATE", false, true);
        }

        UUID verificationId = jdbc.query("""
                select id from iam.identity_verification
                where provider_code = ? and provider_reference = ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                provider.providerCode(), providerReference).stream().findFirst()
                .orElseThrow(() -> ApiProblem.notFound("KYC_VERIFICATION_NOT_FOUND", "No existe verificación local para la referencia del proveedor."));

        IdentityVerificationPort.VerificationDecision decision;
        try {
            decision = provider.pollDecision(providerReference, Instant.now()).orElse(null);
        } catch (IdentityProviderException ex) {
            throw ApiProblem.unavailable(ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            throw ApiProblem.unavailable("KYC_PROVIDER_UNAVAILABLE", "No fue posible consultar la decisión KYC.");
        }

        if (decision == null || decision.status() == IdentityVerificationPort.DecisionStatus.PENDING) {
            jdbc.update("""
                    update iam.identity_provider_event
                    set applied_at = clock_timestamp(), normalized_status = 'PENDING'
                    where id = ?
                    """, eventId);
            return new WebhookResult(provider.providerCode(), providerReference, "PENDING", false, false);
        }

        boolean verified = decision.status() == IdentityVerificationPort.DecisionStatus.VERIFIED;
        Timestamp decidedAt = verified ? Timestamp.from(decision.decidedAt()) : null;
        jdbc.update("""
                update iam.identity_verification
                set status = ?::platform.verification_status,
                    verified_adult = ?,
                    legal_country_code = ?,
                    date_of_birth = null,
                    verified_at = ?::timestamptz
                where id = ?
                """, decision.status().name(), decision.verifiedAdult(), decision.legalCountryCode(),
                decidedAt, verificationId);

        jdbc.update("""
                update iam.identity_provider_session
                set normalized_status = ?::platform.verification_status,
                    last_provider_event_at = clock_timestamp(), updated_at = clock_timestamp()
                where verification_id = ?
                """, decision.status().name(), verificationId);

        UUID userId = jdbc.queryForObject("select user_id from iam.identity_verification where id = ?", UUID.class, verificationId);
        if (verified && decision.identityVerified() && decision.verifiedAdult()) {
            // KYC may activate only a still-pending account. It must never override
            // Safety/administrative RESTRICTED, SUSPENDED or CLOSED states.
            jdbc.update("""
                    update iam.app_user
                    set account_status = 'ACTIVE'
                    where id = ? and account_status = 'PENDING_VERIFICATION'
                    """, userId);
        }

        jdbc.update("""
                update iam.identity_provider_event
                set applied_at = clock_timestamp(), normalized_status = ?::platform.verification_status
                where id = ?
                """, decision.status().name(), eventId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider.providerCode());
        metadata.put("status", decision.status().name());
        metadata.put("verifiedAdult", decision.verifiedAdult());
        if (decision.reasonCode() != null) metadata.put("reasonCode", decision.reasonCode());
        persistence.audit(userId, "KYC_PROVIDER_DECISION_APPLIED", "IDENTITY_VERIFICATION", verificationId, metadata);
        persistence.outbox("USER", userId, "IDENTITY_VERIFICATION_UPDATED",
                Map.of("userId", userId.toString(), "verificationId", verificationId.toString(),
                        "status", decision.status().name(), "verifiedAdult", decision.verifiedAdult()));

        return new WebhookResult(provider.providerCode(), providerReference,
                decision.status().name(), verified, false);
    }

    private IdentityVerificationPort requiredConfiguredProvider() {
        if (configuredProvider.isBlank()) {
            throw ApiProblem.unavailable("KYC_PROVIDER_NOT_CONFIGURED", "No hay proveedor KYC configurado.");
        }
        return requiredProvider(configuredProvider);
    }

    private IdentityVerificationPort requiredProvider(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return providers.stream()
                .filter(p -> p.providerCode().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> ApiProblem.unavailable("KYC_PROVIDER_NOT_AVAILABLE", "Proveedor KYC no disponible en runtime."));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash KYC webhook", ex);
        }
    }

    private record UserRow(UUID id, String accountStatus) {}

    public record StartResult(UUID verificationId, String provider, String verificationUrl, String status) {}

    public record StatusResult(UUID verificationId, String provider, String status, boolean verifiedAdult,
                               String legalCountryCode, Instant verifiedAt, Instant expiresAt, Instant createdAt) {}

    public record WebhookResult(String provider, String providerReference, String status,
                                boolean accountEligibleForActivation, boolean duplicate) {}
}

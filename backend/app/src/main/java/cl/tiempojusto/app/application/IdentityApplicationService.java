package cl.tiempojusto.app.application;

import cl.tiempojusto.app.api.ApiProblem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityApplicationService {
    private final JdbcTemplate jdbc;
    private final ApplicationPersistenceSupport persistence;

    public IdentityApplicationService(JdbcTemplate jdbc, ApplicationPersistenceSupport persistence) {
        this.jdbc = jdbc;
        this.persistence = persistence;
    }

    @Transactional
    public RegistrationResult registerSandbox(RegistrationRequest request) {
        if (request == null) throw ApiProblem.badRequest("REGISTER_REQUEST_REQUIRED", "Request requerida.");
        String role = normalizedRole(request.role());
        if (request.email() == null || request.email().isBlank()) {
            throw ApiProblem.badRequest("EMAIL_REQUIRED", "Email requerido para el registro sandbox.");
        }
        if (request.username() == null || request.username().isBlank()) {
            throw ApiProblem.badRequest("USERNAME_REQUIRED", "Username requerido.");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw ApiProblem.badRequest("DISPLAY_NAME_REQUIRED", "Display name requerido.");
        }
        if (request.publicAge() < 18) {
            throw ApiProblem.badRequest("ADULT_ONLY", "TiempoJusto requiere personas adultas.");
        }

        UUID userId = UUID.randomUUID();
        String publicId = "TJ" + userId.toString().replace("-", "").substring(0, 16).toUpperCase();
        jdbc.update("""
                insert into iam.app_user(id, public_id, role, account_status, email)
                values (?, ?, ?::platform.user_role, 'PENDING_VERIFICATION', ?)
                """, userId, publicId, role, request.email().trim());
        jdbc.update("""
                insert into iam.public_identity(user_id, display_name, username, public_age)
                values (?, ?, ?, ?)
                """, userId, request.displayName().trim(), request.username().trim(), request.publicAge());

        UUID hostProfileId = null;
        if ("HOST".equals(role)) {
            hostProfileId = UUID.randomUUID();
            jdbc.update("""
                    insert into profile.host_profile(id, user_id, supports_in_person, supports_online, profile_status)
                    values (?, ?, false, ?, 'DRAFT')
                    """, hostProfileId, userId, request.supportsOnline());
        }

        persistence.audit(userId, "SANDBOX_USER_REGISTERED", "USER", userId,
                Map.of("role", role, "publicId", publicId));
        persistence.outbox("USER", userId, "USER_REGISTERED",
                Map.of("userId", userId.toString(), "role", role));

        return new RegistrationResult(userId, publicId, role, "PENDING_VERIFICATION", hostProfileId);
    }

    @Transactional
    public VerificationResult verifySandboxAdult(UUID userId, VerificationRequest request) {
        if (request == null || request.dateOfBirth() == null) {
            throw ApiProblem.badRequest("DATE_OF_BIRTH_REQUIRED", "Fecha de nacimiento requerida para KYC sandbox.");
        }
        String country = request.legalCountryCode() == null ? "" : request.legalCountryCode().trim().toUpperCase();
        if (country.length() != 2) {
            throw ApiProblem.badRequest("COUNTRY_CODE_INVALID", "legalCountryCode debe tener 2 letras.");
        }
        Integer exists = jdbc.queryForObject("select count(*) from iam.app_user where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw ApiProblem.notFound("USER_NOT_FOUND", "Usuario no existe.");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean adult = !request.dateOfBirth().isAfter(today) && Period.between(request.dateOfBirth(), today).getYears() >= 18;
        if (!adult) throw ApiProblem.forbidden("KYC_ADULT_REQUIRED", "KYC sandbox no valida mayoría de edad.");

        UUID verificationId = UUID.randomUUID();
        String providerRef = "sandbox-kyc-" + verificationId;
        jdbc.update("""
                insert into iam.identity_verification(
                    id, user_id, provider_code, provider_reference, status,
                    verified_adult, legal_country_code, date_of_birth, verified_at)
                values (?, ?, 'SANDBOX', ?, 'VERIFIED', true, ?, ?, clock_timestamp())
                """, verificationId, userId, providerRef, country, request.dateOfBirth());
        jdbc.update("update iam.app_user set account_status = 'ACTIVE' where id = ?", userId);

        String role = jdbc.queryForObject("select role::text from iam.app_user where id = ?", String.class, userId);
        UUID hostProfileId = null;
        if ("HOST".equals(role)) {
            hostProfileId = jdbc.queryForObject("select id from profile.host_profile where user_id = ?", UUID.class, userId);
            Integer videoCount = jdbc.queryForObject(
                    "select count(*) from profile.profile_media where profile_id = ? and media_type = 'VIDEO'",
                    Integer.class, hostProfileId);
            if (videoCount != null && videoCount == 0) {
                jdbc.update("""
                        insert into profile.profile_media(profile_id, media_type, storage_key, duration_seconds, moderation_status)
                        values (?, 'VIDEO', ?, 20, 'ACTIVE')
                        """, hostProfileId, "sandbox://profile-video/" + hostProfileId);
            }
            jdbc.update("update profile.host_profile set profile_status = 'ACTIVE' where id = ?", hostProfileId);
        }

        persistence.audit(userId, "KYC_SANDBOX_VERIFIED_ADULT", "IDENTITY_VERIFICATION", verificationId,
                Map.of("provider", "SANDBOX", "verifiedAdult", true));
        persistence.outbox("USER", userId, "IDENTITY_VERIFIED",
                Map.of("userId", userId.toString(), "verifiedAdult", true));

        return new VerificationResult(verificationId, userId, "VERIFIED", true, "ACTIVE", hostProfileId);
    }

    private static String normalizedRole(String role) {
        if (role == null) throw ApiProblem.badRequest("ROLE_REQUIRED", "Rol requerido.");
        String normalized = role.trim().toUpperCase();
        if (!(normalized.equals("HOST") || normalized.equals("BIDDER"))) {
            throw ApiProblem.badRequest("ROLE_INVALID", "El registro público admite HOST o BIDDER.");
        }
        return normalized;
    }

    public record RegistrationRequest(String role, String email, String username, String displayName,
                                      int publicAge, boolean supportsOnline) {}
    public record RegistrationResult(UUID userId, String publicId, String role, String accountStatus,
                                     UUID hostProfileId) {}
    public record VerificationRequest(LocalDate dateOfBirth, String legalCountryCode) {}
    public record VerificationResult(UUID verificationId, UUID userId, String verificationStatus,
                                     boolean verifiedAdult, String accountStatus, UUID hostProfileId) {}
}

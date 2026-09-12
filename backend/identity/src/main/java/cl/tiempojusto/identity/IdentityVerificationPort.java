package cl.tiempojusto.identity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider-neutral KYC/identity seam.
 *
 * Implementations must keep provider documents, selfie/video payloads and biometric
 * templates outside TiempoJusto. Only opaque provider references and normalized
 * verification outcomes are allowed to cross this port.
 */
public interface IdentityVerificationPort {
    String providerCode();

    IdentityProviderCapabilities capabilities();

    VerificationSession start(StartVerification command);

    Optional<VerificationDecision> pollDecision(String providerReference, Instant now);

    boolean verifyWebhook(String rawBody, Map<String, String> headers);

    Optional<String> providerReferenceFromWebhook(String rawBody);

    record StartVerification(UUID userId, String callbackUrl, Instant now) {
        public StartVerification {
            if (userId == null) throw new IllegalArgumentException("userId is required");
            if (callbackUrl == null || callbackUrl.isBlank()) {
                throw new IllegalArgumentException("callbackUrl is required");
            }
            if (now == null) throw new IllegalArgumentException("now is required");
        }
    }

    record VerificationSession(String providerReference, String verificationUrl, Instant createdAt) {
        public VerificationSession {
            if (providerReference == null || providerReference.isBlank()) {
                throw new IllegalArgumentException("providerReference is required");
            }
            if (verificationUrl == null || verificationUrl.isBlank()) {
                throw new IllegalArgumentException("verificationUrl is required");
            }
            if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        }
    }

    enum DecisionStatus {
        PENDING,
        VERIFIED,
        REVIEW,
        REJECTED,
        EXPIRED
    }

    record VerificationDecision(
            String providerReference,
            DecisionStatus status,
            boolean identityVerified,
            boolean verifiedAdult,
            String legalCountryCode,
            String reasonCode,
            Instant decidedAt) {
        public VerificationDecision {
            if (providerReference == null || providerReference.isBlank()) {
                throw new IllegalArgumentException("providerReference is required");
            }
            if (status == null) throw new IllegalArgumentException("status is required");
            if (decidedAt == null) throw new IllegalArgumentException("decidedAt is required");
            if (status == DecisionStatus.VERIFIED && (!identityVerified || !verifiedAdult)) {
                throw new IllegalArgumentException("VERIFIED requires identityVerified and verifiedAdult");
            }
            if (legalCountryCode != null && legalCountryCode.length() != 2) {
                throw new IllegalArgumentException("legalCountryCode must have 2 letters when present");
            }
        }
    }
}

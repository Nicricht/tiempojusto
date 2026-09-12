package cl.tiempojusto.identity;

import cl.tiempojusto.identity.IdentityVerificationPort.DecisionStatus;
import cl.tiempojusto.identity.IdentityVerificationPort.VerificationDecision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Privacy-minimizing mapper for Veriff decision data.
 *
 * Date of birth is used transiently to prove adulthood and is intentionally not
 * carried in the normalized decision returned to TiempoJusto persistence.
 */
public final class VeriffDecisionMapper {
    private VeriffDecisionMapper() {}

    public static VerificationDecision map(
            String providerReference,
            String providerStatus,
            Integer providerCode,
            LocalDate dateOfBirth,
            String documentCountry,
            String reasonCode,
            Instant decidedAt) {

        if (decidedAt == null) decidedAt = Instant.now();
        String status = providerStatus == null ? "" : providerStatus.trim().toLowerCase(Locale.ROOT);
        String country = normalizeCountry(documentCountry);

        return switch (status) {
            case "approved" -> approved(providerReference, providerCode, dateOfBirth, country, reasonCode, decidedAt);
            case "declined" -> new VerificationDecision(providerReference, DecisionStatus.REJECTED,
                    false, false, country, reasonCode, decidedAt);
            case "resubmission_requested" -> new VerificationDecision(providerReference, DecisionStatus.REVIEW,
                    false, false, country, reasonCode, decidedAt);
            case "expired", "abandoned" -> new VerificationDecision(providerReference, DecisionStatus.EXPIRED,
                    false, false, country, reasonCode, decidedAt);
            default -> new VerificationDecision(providerReference, DecisionStatus.PENDING,
                    false, false, country, reasonCode, decidedAt);
        };
    }

    private static VerificationDecision approved(
            String providerReference,
            Integer providerCode,
            LocalDate dateOfBirth,
            String country,
            String reasonCode,
            Instant decidedAt) {

        // Veriff documents code 9001 as approved. A status mismatch or missing DOB
        // is deliberately not enough to activate an adult-only account.
        if (providerCode == null || providerCode != 9001 || dateOfBirth == null) {
            return new VerificationDecision(providerReference, DecisionStatus.REVIEW,
                    false, false, country, reasonCode == null ? "ADULT_PROOF_INCOMPLETE" : reasonCode, decidedAt);
        }

        LocalDate decisionDate = decidedAt.atZone(ZoneOffset.UTC).toLocalDate();
        boolean validDob = !dateOfBirth.isAfter(decisionDate);
        boolean adult = validDob && Period.between(dateOfBirth, decisionDate).getYears() >= 18;
        if (!adult) {
            return new VerificationDecision(providerReference, DecisionStatus.REJECTED,
                    true, false, country, "ADULT_REQUIRED", decidedAt);
        }

        return new VerificationDecision(providerReference, DecisionStatus.VERIFIED,
                true, true, country, reasonCode, decidedAt);
    }

    private static String normalizeCountry(String country) {
        if (country == null || country.isBlank()) return null;
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        return normalized.length() == 2 ? normalized : null;
    }
}

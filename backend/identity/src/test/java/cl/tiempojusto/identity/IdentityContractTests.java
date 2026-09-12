package cl.tiempojusto.identity;

import java.time.Instant;
import java.time.LocalDate;

public final class IdentityContractTests {
    private static int passed;

    public static void main(String[] args) {
        testCapabilitiesFailClosed();
        testApprovedAdult();
        testApprovedMinorRejected();
        testApprovedWithoutDobGoesReview();
        testDeclinedRejected();
        testResubmissionReview();
        testExpired();
        System.out.println("PASS: " + passed + "/7 Identity contract tests");
    }

    private static void testCapabilitiesFailClosed() {
        var good = new IdentityProviderCapabilities(true, true, true, true, true, true);
        var missingSignature = new IdentityProviderCapabilities(true, true, false, true, true, true);
        check(good.satisfiesTiempoJustoV1(), "complete provider capabilities should pass");
        check(!missingSignature.satisfiesTiempoJustoV1(), "missing signed webhooks must fail closed");
        passed++;
    }

    private static void testApprovedAdult() {
        var now = Instant.parse("2026-09-12T00:00:00Z");
        var result = VeriffDecisionMapper.map("session-1", "approved", 9001,
                LocalDate.of(2000, 1, 1), "cl", null, now);
        check(result.status() == IdentityVerificationPort.DecisionStatus.VERIFIED, "adult approval should verify");
        check(result.identityVerified() && result.verifiedAdult(), "verified decision needs identity + adult");
        check("CL".equals(result.legalCountryCode()), "country should normalize to ISO alpha-2 uppercase");
        passed++;
    }

    private static void testApprovedMinorRejected() {
        var result = VeriffDecisionMapper.map("session-2", "approved", 9001,
                LocalDate.of(2010, 1, 1), "CL", null, Instant.parse("2026-09-12T00:00:00Z"));
        check(result.status() == IdentityVerificationPort.DecisionStatus.REJECTED, "minor must be rejected");
        check(!result.verifiedAdult(), "minor cannot be marked adult");
        passed++;
    }

    private static void testApprovedWithoutDobGoesReview() {
        var result = VeriffDecisionMapper.map("session-3", "approved", 9001,
                null, "CL", null, Instant.parse("2026-09-12T00:00:00Z"));
        check(result.status() == IdentityVerificationPort.DecisionStatus.REVIEW,
                "approved without DOB must not auto-activate adult account");
        passed++;
    }

    private static void testDeclinedRejected() {
        var result = VeriffDecisionMapper.map("session-4", "declined", 9103,
                LocalDate.of(1990, 1, 1), "CL", "DOCUMENT_INVALID", Instant.now());
        check(result.status() == IdentityVerificationPort.DecisionStatus.REJECTED, "declined should reject");
        passed++;
    }

    private static void testResubmissionReview() {
        var result = VeriffDecisionMapper.map("session-5", "resubmission_requested", null,
                null, null, "RESUBMIT", Instant.now());
        check(result.status() == IdentityVerificationPort.DecisionStatus.REVIEW, "resubmission should require review/user action");
        passed++;
    }

    private static void testExpired() {
        var result = VeriffDecisionMapper.map("session-6", "expired", null,
                null, null, null, Instant.now());
        check(result.status() == IdentityVerificationPort.DecisionStatus.EXPIRED, "expired should expire");
        passed++;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

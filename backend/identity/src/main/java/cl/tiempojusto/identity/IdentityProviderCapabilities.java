package cl.tiempojusto.identity;

/**
 * Explicit provider capability matrix. Runtime activation is fail-closed: a
 * provider must demonstrate all capabilities required by TiempoJusto before it
 * can activate accounts.
 */
public record IdentityProviderCapabilities(
        boolean documentIdentity,
        boolean adultAgeVerification,
        boolean signedWebhooks,
        boolean livenessAvailable,
        boolean rawDocumentsStayAtProvider,
        boolean rawBiometricsStayAtProvider) {

    public boolean satisfiesTiempoJustoV1() {
        return documentIdentity
                && adultAgeVerification
                && signedWebhooks
                && rawDocumentsStayAtProvider
                && rawBiometricsStayAtProvider;
    }
}

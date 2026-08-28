package com.leadflow.qualification;

/**
 * Demande de diagnostic.
 *
 * @param apiKey cle a eprouver. Absente, c'est la cle en service qui est eprouvee — ce qui
 *     permet de verifier apres coup qu'un reglage enregistre fonctionne toujours
 */
public record IntentTestRequest(String apiKey) {
}

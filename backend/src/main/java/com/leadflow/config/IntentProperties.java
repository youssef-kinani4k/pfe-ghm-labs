package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages de l'analyse d'intention.
 *
 * <p>La cle d'API est globale a l'instance et non portee par le client : l'analyse
 * d'intention est un service que l'agence rend a ses clients, pas un reglage de tenant.
 * Elle vient de l'environnement et n'apparait jamais en base ni dans un journal.
 */
@ConfigurationProperties(prefix = "leadflow.intent")
public record IntentProperties(Gemini gemini) {

    /**
     * @param enabled cable l'analyseur ; a {@code false}, seul l'analyseur lexical existe
     * @param apiKey absente ou vide : l'application demarre et qualifie en mode RULES
     * @param baseUrl racine de l'API. Propriete et non constante : c'est ce qui permet a un
     *     test d'integration de la pointer vers un port mort et de verifier que le mode
     *     degrade est cable de bout en bout, sans aucun appel reseau sortant
     * @param model identifiant du modele, propriete pour ne pas etre fige dans le code
     * @param maxMessageChars garde-fou sur le texte envoye au modele
     */
    public record Gemini(
            boolean enabled,
            String apiKey,
            String baseUrl,
            String model,
            Duration connectTimeout,
            Duration readTimeout,
            int maxMessageChars) {
    }
}

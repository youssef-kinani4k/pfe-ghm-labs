package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Garde-fou de volume sur le webhook. La signature protege l'authenticite d'une soumission,
 * pas leur nombre : sans plafond, une boutique compromise ou un formulaire en boucle
 * remplit la file et la base au rythme du reseau.
 *
 * <p>Le prefixe est {@code leadflow.webhook} et non {@code leadflow.capture} : tous les
 * reglages de cette route y vivent deja.
 *
 * @param actif presence du filtre ; le mettre a faux le retire de la chaine
 * @param requetesParMinute debit rendu au seau, par cle publique
 * @param rafale capacite du seau, donc le pic absorbable sans refus
 * @param clesSuiviesMax plafond d'entrees de la carte, au-dela duquel les plus anciennement
 *     vues sont evincees — une cle inconnue consomme une entree, et marteler des cles
 *     aleatoires ferait enfler la memoire sans ce plafond
 * @param fenetreInactivite au-dela, un seau inactif est evacue par le balayage
 */
@ConfigurationProperties(prefix = "leadflow.webhook.rate-limit")
public record RateLimitProperties(
        boolean actif,
        int requetesParMinute,
        int rafale,
        int clesSuiviesMax,
        Duration fenetreInactivite) {
}

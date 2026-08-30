package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Garde-fou de volume sur le webhook. La signature protege l'authenticite d'une soumission,
 * pas leur nombre : sans plafond, une boutique compromise ou un formulaire en boucle
 * remplit la file et la base au rythme du reseau.
 *
 * <p>Le prefixe est {@code leadflow.webhook} et non {@code leadflow.capture} : tous les
 * reglages de cette route y vivent deja.
 *
 * <p><b>Les valeurs de repli comptent.</b> {@code @ConditionalOnProperty(matchIfMissing =
 * true)} enregistre le filtre meme quand {@code leadflow.webhook.rate-limit} est absent en
 * entier ; sans {@code @DefaultValue}, le binding par constructeur donnerait alors {@code
 * rafale = 0} (tout est refuse), {@code requetesParMinute = 0} (division par zero dans le
 * calcul de {@code Retry-After}) et {@code fenetreInactivite = null} (NPE dans le balayage
 * programme). Les valeurs ci-dessous recopient exactement celles d'{@code application.yml} :
 * le fichier n'est donc plus le seul rempart entre l'application et cet etat.
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
        @DefaultValue("true") boolean actif,
        @DefaultValue("60") int requetesParMinute,
        @DefaultValue("120") int rafale,
        @DefaultValue("10000") int clesSuiviesMax,
        @DefaultValue("10m") Duration fenetreInactivite) {
}

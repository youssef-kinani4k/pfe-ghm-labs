package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres de verification des webhooks entrants. Le secret de signature n'est pas ici :
 * chaque client a le sien, porte par {@code client.hmac_secret}, chiffre au repos.
 *
 * @param signatureHeader nom de l'en-tete portant {@code t=...,v1=...}
 * @param tolerance ecart maximal accepte entre l'horodatage signe et l'heure du serveur,
 *     dans les deux sens : une horloge client en avance ne doit pas ouvrir la fenetre
 * @param maxPayloadBytes taille de corps au-dela de laquelle la requete est refusee
 * @param relayAfter age minimal d'une ligne non publiee avant que le filet la reprenne
 * @param relayInterval periode de balayage du filet
 * @param transitionSecret duree pendant laquelle le secret precedent d'une boutique reste
 *     accepte apres une rotation. Globale a l'instance, comme le fuseau des series de F13 :
 *     c'est un parametre d'exploitation de l'agence, pas une caracteristique du client.
 */
@ConfigurationProperties(prefix = "leadflow.webhook")
public record WebhookProperties(
        String signatureHeader,
        Duration tolerance,
        int maxPayloadBytes,
        Duration relayAfter,
        Duration relayInterval,
        Duration transitionSecret) {
}

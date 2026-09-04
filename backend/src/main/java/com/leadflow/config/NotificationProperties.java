package com.leadflow.config;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages du canal de notification.
 *
 * <p>Globaux a l'instance et non portes par le client : l'agence exploite un seul relais
 * d'envoi pour toutes ses boutiques, comme elle exploite une seule cle d'analyse
 * d'intention. Ce n'est pas un reglage de tenant. Ce qui se regle par boutique, c'est le
 * seuil a partir duquel un lead merite qu'on derange quelqu'un, et il vit dans
 * {@code client.scoring_config}.
 *
 * <p>Rien n'est en dur : tout vient de l'environnement, et <b>le profil dev ne porte aucun
 * repli</b>. Un repli pointant vers un serveur imaginaire ferait echouer chaque lead chaud
 * en developpement et remplirait la DLQ de morts sans interet.
 *
 * @param urlFiche gabarit de l'adresse de la fiche du lead, {@code {id}} etant remplace.
 *     C'est un fait et non une decision de presentation : le commercial doit pouvoir ouvrir
 *     son lead, quel que soit le canal qui le previent.
 */
@ConfigurationProperties(prefix = "leadflow.notification")
public record NotificationProperties(Smtp smtp, String urlFiche) {

    /** L'adresse de la fiche d'un lead, prete a etre citee par un canal. */
    public String urlDe(UUID leadId) {
        return urlFiche.replace("{id}", leadId.toString());
    }

    /**
     * @param enabled eteint l'envoi sans effacer les reglages
     * @param host absent ou vide : le canal est inerte et l'application demarre quand meme.
     *     C'est le parti de {@code GEMINI_API_KEY} — une instance sans relais doit
     *     continuer a traiter des leads, l'alerte etant un confort et non le pipeline.
     * @param from adresse d'expedition, unique pour l'instance
     * @param timeout plafond de connexion et de lecture vers le relais
     */
    public record Smtp(
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            String from,
            Duration timeout) {

        /** Vrai quand un envoi est possible. Le canal ne tente rien sinon. */
        public boolean configure() {
            return enabled && host != null && !host.isBlank();
        }
    }
}

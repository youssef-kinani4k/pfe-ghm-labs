package com.leadflow.notification.model;

/**
 * Ce qu'un canal a besoin de savoir pour prevenir un commercial.
 *
 * <p><b>Aucun terme propre a un canal ici.</b> Ni objet, ni expediteur, ni corps HTML : ce
 * sont des faits, et c'est l'adaptateur qui compose le message. C'est l'invariant de
 * {@code crm/model} transpose, et il se casse aussi facilement — des qu'un champ SMTP
 * remonte dans ce record, un second canal (tache d'agenda dans l'ERP, webhook sortant)
 * devient impossible sans reecriture.
 *
 * <p>{@code urlFiche} est un fait et non une decision de presentation : le commercial doit
 * pouvoir ouvrir le lead depuis l'alerte, quel que soit le canal qui le previent.
 *
 * <p>Les champs du prospect autres que l'e-mail peuvent etre nuls : la qualification ne fait
 * echouer un lead que sur son email, tout le reste passe a {@code null} quand il est
 * illisible. Un canal doit donc composer son message sans supposer qu'ils existent.
 */
public record NotificationLead(
        String nomCommercial,
        String adresseCommercial,
        String nomProspect,
        String emailProspect,
        String societeProspect,
        int score,
        String intention,
        String urlFiche) {
}

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
 * <p><b>Aucun lien vers le dashboard n'est porte ici, et c'est delibere.</b> Le commercial
 * n'y a aucun compte : la console est une console d'agence, un seul modele d'utilisateur et
 * aucun role. Un lien l'enverrait sur un ecran de connexion qu'il ne peut pas franchir, et
 * un lien mort dans une alerte apprend surtout a ignorer les suivantes. Le message doit donc
 * porter <b>de quoi agir</b> — le telephone et ce que le prospect a ecrit — plutot que de
 * renvoyer ailleurs.
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
        String telephoneProspect,
        String societeProspect,
        int score,
        String intention,
        String messageProspect) {
}

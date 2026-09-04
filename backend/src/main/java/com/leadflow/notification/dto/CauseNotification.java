package com.leadflow.notification.dto;

/**
 * Pourquoi un diagnostic du canal a echoue.
 *
 * <p>Enumeration et non message libre, comme {@code CauseIntent} : l'ecran doit pouvoir dire
 * quoi faire — renseigner un hote, corriger un identifiant, verifier le reseau — sans
 * analyser un texte rendu par un relais, qui varie d'un serveur a l'autre et n'est pas
 * traduit.
 */
public enum CauseNotification {

    /** Le relais a accepte le message : la chaine complete fonctionne. */
    OK,

    /** Aucun hote de relais n'est defini, ou le canal est eteint. */
    NON_CONFIGURE,

    /** Aucune adresse a eprouver : la demande n'en portait pas. */
    DESTINATAIRE_ABSENT,

    /** Rien au bout du fil : hote faux, port ferme, reseau ou pare-feu. */
    RELAIS_INJOIGNABLE,

    /** Le relais repond mais refuse les identifiants fournis. */
    AUTHENTIFICATION_REFUSEE,

    /** Le relais repond, s'authentifie, et refuse quand meme le message. */
    ENVOI_REFUSE
}

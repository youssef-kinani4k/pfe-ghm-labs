package com.leadflow.qualification;

/**
 * Pourquoi un diagnostic de cle a echoue.
 *
 * <p>Enumeration et non message libre : l'ecran doit pouvoir dire quoi faire — refaire la
 * cle, attendre le quota, verifier le reseau — sans analyser un texte rendu par Google, qui
 * peut changer et n'est pas traduit.
 */
public enum CauseIntent {

    /** La cle fonctionne : le modele a rendu une intention du vocabulaire. */
    OK,

    /** Aucune cle a eprouver : ni dans la demande, ni enregistree. */
    CLE_ABSENTE,

    /** Cle invalide, revoquee, ou API non activee sur le projet Google. */
    CLE_REFUSEE,

    /** Quota epuise ou cadence trop elevee. La cle est bonne, il faut attendre. */
    QUOTA_DEPASSE,

    /** Rien au bout du fil : reseau, proxy, ou racine d'API mal saisie. */
    INJOIGNABLE,

    /** Panne chez le fournisseur. */
    ERREUR_SERVEUR,

    /** Le modele a repondu, mais hors du vocabulaire attendu. */
    REPONSE_INATTENDUE
}

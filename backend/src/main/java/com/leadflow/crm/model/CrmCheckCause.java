package com.leadflow.crm.model;

/**
 * Pourquoi une sonde d'acces a abouti ou echoue.
 *
 * <p>Enumeration et non message libre : l'ecran doit pouvoir phraser l'echec en francais
 * pour un utilisateur non technique. Un message brut d'adaptateur — « I/O error on POST
 * request ... Connection refused: getsockopt » — ne se traduit pas.
 *
 * <p>Aucun terme propre a un fournisseur n'y figure : c'est l'adaptateur qui sait qu'un 401
 * Dolibarr et un refus JSON-RPC Odoo disent la meme chose.
 */
public enum CrmCheckCause {
    JOIGNABLE,
    INJOIGNABLE,
    IDENTIFIANTS_REFUSES,
    CIBLE_INCONNUE,
    DESTINATION_REFUSEE,
    REPONSE_INATTENDUE
}

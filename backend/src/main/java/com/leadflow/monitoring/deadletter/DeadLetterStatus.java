package com.leadflow.monitoring.deadletter;

/**
 * Cycle de vie d'une ligne du journal. {@code REPLAYED} et {@code DISCARDED} sont tous deux
 * terminaux : un rejeu qui echoue de nouveau produit une <b>nouvelle</b> ligne, l'historique
 * se lit et ne s'ecrase pas.
 */
public enum DeadLetterStatus {

    /** Mort constatee, en attente d'une decision humaine. */
    PENDING,

    /** Republiee sur la file d'origine par un operateur. */
    REPLAYED,

    /** Ecartee sans rejeu : doublon, message de test, echec definitivement compris. */
    DISCARDED
}

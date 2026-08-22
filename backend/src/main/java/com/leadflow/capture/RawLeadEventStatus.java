package com.leadflow.capture;

/**
 * Cycle de vie d'un evenement brut, de sa reception a sa publication sur le broker.
 *
 * <p>{@link #FAILED} et {@link #DISCARDED} disent deux choses opposees, et les confondre
 * coute cher : {@code PendingEventRelay} rebalaye {@code FAILED}, donc y ranger un echec
 * definitif condamnerait l'evenement a etre republie sans fin.
 */
public enum RawLeadEventStatus {

    /** Persiste, pas encore publie. */
    RECEIVED,

    /** Publie sur le broker. */
    PUBLISHED,

    /** Publication en echec : transitoire, le filet de republication le reprendra. */
    FAILED,

    /**
     * Terminal : l'evenement ne produira jamais de lead — payload sans email exploitable.
     * Rien ne le rejoue, et {@code failure_reason} en garde la raison pour le monitoring.
     */
    DISCARDED
}

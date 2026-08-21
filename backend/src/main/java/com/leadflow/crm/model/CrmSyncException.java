package com.leadflow.crm.model;

/**
 * Echec de synchronisation vers un ERP. Levee par un adaptateur, elle laisse le message
 * repartir en retry puis en DLQ plutot que d'etre avalee silencieusement.
 *
 * <p>Elle transporte l'etat partiel obtenu avant l'echec : si le tiers a ete cree mais que
 * l'opportunite a echoue, l'appelant doit pouvoir tracer la reference du tiers, sans quoi le
 * rejeu le recreerait. Ne jamais y placer de secret : le message finit en base et dans les
 * journaux.
 */
public class CrmSyncException extends RuntimeException {

    private final String providerId;
    private final CrmSyncState partialState;

    public CrmSyncException(String providerId, String message, Throwable cause) {
        this(providerId, message, cause, CrmSyncState.VIERGE);
    }

    private CrmSyncException(
            String providerId, String message, Throwable cause, CrmSyncState partialState) {
        super(message, cause);
        this.providerId = providerId;
        this.partialState = partialState;
    }

    public String providerId() {
        return providerId;
    }

    /** References obtenues avant l'echec. Jamais nul : {@link CrmSyncState#VIERGE} par defaut. */
    public CrmSyncState partialState() {
        return partialState;
    }

    /** Meme echec, enrichi de ce qui avait deja ete cree. */
    public CrmSyncException avecEtat(CrmSyncState etat) {
        return new CrmSyncException(providerId, getMessage(), getCause(), etat);
    }
}

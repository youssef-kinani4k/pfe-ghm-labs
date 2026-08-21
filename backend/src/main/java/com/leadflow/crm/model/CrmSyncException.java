package com.leadflow.crm.model;

/**
 * Echec de synchronisation vers un ERP. Levee par un adaptateur, elle laisse le message
 * repartir en retry puis en DLQ plutot que d'etre avalee silencieusement.
 */
public class CrmSyncException extends RuntimeException {

    private final String providerId;

    public CrmSyncException(String providerId, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
    }

    public String providerId() {
        return providerId;
    }
}

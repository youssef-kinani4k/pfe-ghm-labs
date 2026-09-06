package com.leadflow.crm;

/**
 * Ce que raconte une ligne de {@code crm_sync_attempt}.
 *
 * <p>Une {@code REAFFECTATION} ne cree rien dans l'ERP : elle corrige le responsable d'un
 * lead qui s'y trouve deja, et ne renseigne donc que {@code assignee_ref}. La distinguer
 * evite que l'ecran presente une tentative normale comme une synchronisation qui se serait
 * arretee au premier appel.
 */
public enum CrmSyncAttemptNature {
    SYNCHRONISATION,
    REAFFECTATION
}

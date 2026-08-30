package com.leadflow.crm.model;

/**
 * References deja obtenues dans l'ERP lors des tentatives precedentes.
 *
 * <p>C'est l'appelant qui reconstruit cet etat depuis la trace et le passe a l'adaptateur :
 * un adaptateur ne connait pas notre schema et ne lit jamais notre base. Chaque champ non
 * nul dispense l'adaptateur de recreer l'objet correspondant — c'est tout le mecanisme
 * d'idempotence au rejeu.
 */
public record CrmSyncState(
        String accountRef, String contactRef, String opportunityRef, String assigneeRef) {

    /** Aucune tentative anterieure exploitable : tout est a creer. */
    public static final CrmSyncState VIERGE = new CrmSyncState(null, null, null, null);
}

package com.leadflow.crm.model;

/**
 * Lead qualifie, exprime dans le vocabulaire du domaine et non dans celui d'un ERP.
 * C'est ce que chaque adaptateur recoit et traduit vers son propre modele.
 */
public record CrmLead(
        String companyName,
        String firstName,
        String lastName,
        String email,
        String phone,
        String message,
        String detectedIntent,
        int score,
        String countryCode,
        String sector,
        /** Identifiant du commercial dans l'ERP cible, resolu par la couche routing. */
        String assigneeRef) {
}

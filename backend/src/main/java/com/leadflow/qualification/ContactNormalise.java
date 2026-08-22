package com.leadflow.qualification;

/**
 * Identite du prospect apres nettoyage, prete a etre ecrite en base : chaque champ tient
 * dans sa colonne et l'email est exploitable.
 */
public record ContactNormalise(
        String email,
        String phone,
        String message,
        String companyName,
        String firstName,
        String lastName,
        String countryCode,
        String sector) {
}

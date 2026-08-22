package com.leadflow.qualification;

/**
 * Ce que le payload a livre, avant toute normalisation : les valeurs sont telles que le
 * formulaire les a envoyees. Un champ absent vaut {@code null}.
 */
public record ChampsBruts(
        String email,
        String phone,
        String message,
        String companyName,
        String firstName,
        String lastName,
        String countryCode,
        String sector) {
}

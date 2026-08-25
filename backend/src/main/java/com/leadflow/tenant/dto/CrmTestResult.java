package com.leadflow.tenant.dto;

/**
 * Resultat du test.
 *
 * <p>{@code cause} est le nom de l'enumeration, pas une phrase : l'ecran phrase en francais,
 * et un message construit ici obligerait a redeployer le backend pour corriger un libelle.
 */
public record CrmTestResult(boolean ok, String cause, String detail) {
}

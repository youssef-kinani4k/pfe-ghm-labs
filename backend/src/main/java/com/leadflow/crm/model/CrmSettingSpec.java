package com.leadflow.crm.model;

/**
 * Un reglage que le fournisseur attend dans {@code client.crm_config}.
 *
 * <p>{@code secret} vaut vrai pour ce qui ne doit jamais etre reaffiche apres saisie : le
 * formulaire masque le champ, et l'API ne rend pas la valeur enregistree.
 */
public record CrmSettingSpec(String cle, String libelle, boolean secret) {
}

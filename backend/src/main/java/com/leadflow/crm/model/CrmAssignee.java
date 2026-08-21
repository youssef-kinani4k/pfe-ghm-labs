package com.leadflow.crm.model;

/**
 * Commercial a traduire en identifiant utilisateur dans l'ERP cible.
 *
 * <p>Volontairement reduit a ce qui permet de le retrouver : les ERP n'exposent pas la meme
 * fiche utilisateur, et le pivot n'a pas a porter leurs differences.
 */
public record CrmAssignee(String fullName, String email) {
}

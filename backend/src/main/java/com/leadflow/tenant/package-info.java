/**
 * Donnees de reference multi-tenant : le client de l'agence et ses commerciaux.
 *
 * <p>Ce package n'est pas une etape du pipeline. Il porte ce qui parametre toutes les
 * etapes : secret de signature verifie par {@code capture}, regles de scoring lues par
 * {@code qualification}, strategie d'attribution appliquee par {@code routing},
 * coordonnees de l'ERP utilisees par {@code crm}.
 */
package com.leadflow.tenant;

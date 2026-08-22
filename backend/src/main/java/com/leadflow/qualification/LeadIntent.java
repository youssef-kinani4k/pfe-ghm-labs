package com.leadflow.qualification;

/**
 * Vocabulaire ferme des intentions detectables.
 *
 * <p>Ferme a dessein : c'est ce qui borne ce qu'un analyseur externe peut repondre. Une
 * reponse hors de cette liste est refusee et declenche le repli, ce qui neutralise une
 * injection de prompt glissee dans le message du prospect.
 */
public enum LeadIntent {
    DEVIS,
    ACHAT,
    INFORMATION,
    SUPPORT,
    AUTRE
}

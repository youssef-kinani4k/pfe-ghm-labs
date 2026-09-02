package com.leadflow.routing;

/**
 * Issue d'une action. {@code ECHEC} ne concerne que le rejeu d'un message mort : une
 * reattribution n'ecrit sa ligne qu'apres le commit de l'ecriture, donc son echec ne
 * produit aucune ligne.
 */
public enum LeadActionOutcome {
    SUCCES,
    ECHEC
}

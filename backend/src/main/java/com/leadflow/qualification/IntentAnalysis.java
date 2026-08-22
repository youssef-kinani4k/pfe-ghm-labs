package com.leadflow.qualification;

/**
 * Resultat d'une analyse d'intention, avec l'analyseur qui l'a produite. La source n'est pas
 * decorative : elle rend le basculement en mode degrade observable en base plutot
 * qu'affirme dans un journal.
 */
public record IntentAnalysis(LeadIntent intent, IntentSource source) {
}

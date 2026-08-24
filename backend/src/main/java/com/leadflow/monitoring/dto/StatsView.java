package com.leadflow.monitoring.dto;

import java.util.Map;

/**
 * Tout l'ecran de statistiques en un appel. Un endpoint par compteur multiplierait les
 * allers-retours pour un ecran qui se lit d'un bloc.
 *
 * <p>Aucune serie temporelle : sans bibliotheque de graphiques, elle n'aurait aucun
 * consommateur. La requete par jour s'ajoutera le jour ou un graphique existera.
 */
public record StatsView(
        long total,
        Map<String, Long> leadsParStatut,
        Map<String, Long> evenementsParStatut,
        double tauxDeConversion,
        Map<String, Long> leadsParIntention,
        Map<String, Long> leadsParSourceDIntention,
        Map<String, Long> leadsParCommercial,
        Map<String, String> nomsDeCommercial) {
}

package com.leadflow.monitoring.dto;

import java.util.Map;

/**
 * Tout l'ecran de statistiques en un appel. Un endpoint par compteur multiplierait les
 * allers-retours pour un ecran qui se lit d'un bloc.
 *
 * <p>Aucune serie temporelle ici : elles vivent dans {@code SeriesView}, servies par
 * {@code GET /api/stats/series}. La separation n'est pas cosmetique — le dashboard d'accueil
 * paierait sinon le cout du {@code percentile_cont} des delais a chaque chargement, pour des
 * figures qu'il n'affiche pas.
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

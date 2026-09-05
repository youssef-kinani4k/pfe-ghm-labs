package com.leadflow.monitoring.dto;

import java.util.List;

/**
 * Les trois series de l'ecran d'analyse en un appel.
 *
 * <p>Meme parti que {@link StatsView} : l'ecran se lit d'un bloc, et les trois figures
 * partagent la meme periode et la meme boutique. Trois endpoints auraient fait trois
 * allers-retours a chaque changement de periode.
 *
 * <p>Les trois listes portent exactement les memes jours, dans le meme ordre : le frontend
 * les dessine sur un axe commun.
 */
public record SeriesView(
        List<PointVolume> volume,
        List<PointDelai> delais,
        List<PointIntention> intentions) {
}

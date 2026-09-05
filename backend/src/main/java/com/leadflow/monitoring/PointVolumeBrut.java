package com.leadflow.monitoring;

import java.time.LocalDate;

/**
 * Une journee de capture, telle que Postgres la rend.
 *
 * <p>Projection d'interface, comme {@link Comptage} : Spring Data la remplit sans passer par
 * l'entite, ce qui garantit qu'aucune ligne n'est chargee pour compter des lignes.
 *
 * <p>« Brut » parce que la serie qui sort d'ici est trouee — une journee sans capture n'a pas
 * de ligne. C'est {@code SeriesService} qui la comble.
 */
public interface PointVolumeBrut {

    LocalDate getJour();

    long getCaptures();

    long getEcartes();
}

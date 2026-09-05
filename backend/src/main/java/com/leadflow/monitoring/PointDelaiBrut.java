package com.leadflow.monitoring;

import java.time.LocalDate;

/**
 * Le delai capture -> synchronisation ERP d'une journee, en secondes.
 *
 * <p>Deux mesures et pas une moyenne : un seul ERP en timeout a 30 s ecraserait la lecture
 * d'une journee normale a 2 s. La mediane decrit le lead courant, le p95 revele la queue.
 *
 * <p>Les deux sont des {@code Double} et non des {@code double} : une journee sans aucune
 * synchronisation n'a pas de ligne ici, et le service qui comble ce trou doit pouvoir y
 * mettre {@code null}. Zero voudrait dire « delai nul », soit l'inverse du sens.
 */
public interface PointDelaiBrut {

    LocalDate getJour();

    Double getMedianeSecondes();

    Double getP95Secondes();
}

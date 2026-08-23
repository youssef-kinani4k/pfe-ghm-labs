package com.leadflow.monitoring;

/**
 * Projection d'interface d'un {@code group by}. Spring Data la remplit sans passer par
 * l'entite : c'est ce qui garantit qu'aucune ligne n'est chargee pour compter des lignes.
 */
public interface Comptage {

    String getCle();

    long getTotal();
}

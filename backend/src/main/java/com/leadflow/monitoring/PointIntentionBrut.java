package com.leadflow.monitoring;

import java.time.LocalDate;

/**
 * Une journee d'analyse d'intention, repartie entre le modele et le lexique.
 *
 * <p>Les leads sans source ne comptent nulle part : la figure repond « quelle part de
 * l'analyse a ete faite par le modele », pas « combien de leads sont arrives ». Le total des
 * deux colonnes n'est donc pas le nombre de leads du jour.
 */
public interface PointIntentionBrut {

    LocalDate getJour();

    long getGemini();

    long getLexique();
}

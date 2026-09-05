package com.leadflow.monitoring;

/**
 * Fenetre d'analyse hors des trois valeurs admises.
 *
 * <p>La borne n'est pas cosmetique : sans elle, un appel a 100 000 jours ferait balayer les
 * tables entieres. C'est le meme reflexe qui a donne sa pagination a la liste des leads.
 */
public class FenetreInvalideException extends RuntimeException {

    public FenetreInvalideException(int jours) {
        super("Fenetre d'analyse invalide : " + jours + " jours. Valeurs admises : 7, 30, 90.");
    }
}

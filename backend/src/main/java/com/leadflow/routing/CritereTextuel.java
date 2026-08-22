package com.leadflow.routing;

import java.util.Locale;

/**
 * Comparaison des criteres saisis a la main : zone, secteur.
 *
 * <p>Domicile neutre plutot qu'une methode statique portee par l'une des strategies : ni la
 * geographie ni le secteur ne possede cette regle, et une strategie qui importerait un
 * utilitaire de l'autre creerait un couplage que rien ne justifie.
 *
 * <p>La correspondance est <b>exacte</b>, casse et espaces mis a part. Une comparaison par
 * prefixe ferait d'un critere court un piege silencieux : « industrie » capterait
 * « industrie du textile », et l'agence n'aurait aucun moyen de dire qu'elle ne le veut pas.
 */
final class CritereTextuel {

    private CritereTextuel() {
    }

    /** @return {@code false} des qu'une des deux valeurs manque : rien ne peut correspondre */
    static boolean correspond(String valeur, String attendue) {
        if (valeur == null || attendue == null) {
            return false;
        }
        return valeur.trim().toLowerCase(Locale.ROOT)
                .equals(attendue.trim().toLowerCase(Locale.ROOT));
    }
}

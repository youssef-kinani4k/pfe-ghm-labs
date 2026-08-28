package com.leadflow.qualification;

/**
 * Resultat d'un diagnostic de cle.
 *
 * @param ok la cle a permis de classer un message d'exemple
 * @param cause pourquoi, quand ce n'est pas le cas
 * @param detail complement lisible, jamais la cle
 * @param intention l'intention rendue par le modele en cas de succes, pour que l'operateur
 *     voie que la chaine complete fonctionne et pas seulement l'authentification
 */
public record IntentTestResult(boolean ok, CauseIntent cause, String detail, String intention) {
}

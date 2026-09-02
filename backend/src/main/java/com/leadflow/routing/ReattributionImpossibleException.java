package com.leadflow.routing;

/**
 * Refus d'une reattribution pour une raison metier : lead sans commercial, commercial
 * inconnu, inactif, d'une autre boutique, ou deja en place.
 *
 * <p>Une seule exception pour les quatre cas, mappee sur {@code 409} : ce sont tous des
 * conflits avec l'etat courant, et les distinguer par des codes differents n'apprendrait
 * rien a l'ecran, qui affiche le message.
 */
public class ReattributionImpossibleException extends RuntimeException {

    public ReattributionImpossibleException(String message) {
        super(message);
    }
}

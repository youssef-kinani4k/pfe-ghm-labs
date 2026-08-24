package com.leadflow.monitoring;

/**
 * Ressource inconnue du dashboard. Traduite en 404 par {@code ApiExceptionHandler}.
 *
 * <p>Contrairement au refus du webhook, le message est explicite : l'appelant est un
 * operateur deja authentifie, et lui cacher qu'un identifiant n'existe pas ne protegerait
 * rien tout en rendant l'ecran illisible.
 */
public class RessourceIntrouvableException extends RuntimeException {

    public RessourceIntrouvableException(String message) {
        super(message);
    }
}

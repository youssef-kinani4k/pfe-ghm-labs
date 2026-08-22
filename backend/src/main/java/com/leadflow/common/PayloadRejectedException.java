package com.leadflow.common;

import org.springframework.http.HttpStatus;

/**
 * Corps refuse APRES authentification : illisible, sans {@code source}, ou trop gros. La
 * distinction avec {@link WebhookAuthenticationException} est essentielle — ici l'appelant
 * est deja authentifie, on peut donc lui dire ce qui ne va pas sans rien divulguer.
 */
public class PayloadRejectedException extends RuntimeException {

    private final HttpStatus statut;

    public PayloadRejectedException(String message, HttpStatus statut) {
        super(message);
        this.statut = statut;
    }

    public HttpStatus statut() {
        return statut;
    }
}

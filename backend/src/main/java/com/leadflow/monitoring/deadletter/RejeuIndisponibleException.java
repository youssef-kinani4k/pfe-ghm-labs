package com.leadflow.monitoring.deadletter;

/**
 * Traduite en 503 : le broker n'a pas accepte la republication. La ligne reste
 * {@code PENDING}, l'operateur reessaie — c'est exactement la situation ou il ne faut ni
 * marquer, ni perdre.
 */
public class RejeuIndisponibleException extends RuntimeException {

    public RejeuIndisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}

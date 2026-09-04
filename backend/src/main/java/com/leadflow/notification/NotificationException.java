package com.leadflow.notification;

/**
 * Echec technique d'un canal de notification.
 *
 * <p>Elle signifie « reessayer peut marcher » : relais injoignable, authentification
 * refusee, delai depasse. Elle provoque donc les trois tentatives puis la DLQ, ou un humain
 * peut la rejouer. Un echec deterministe — commercial sans adresse — ne leve jamais cette
 * exception : il ecrit une trace et acquitte, aucune repetition ne le reparant.
 */
public class NotificationException extends RuntimeException {

    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}

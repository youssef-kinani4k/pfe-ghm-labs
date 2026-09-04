package com.leadflow.notification.dto;

/**
 * Resultat d'un diagnostic du canal de notification.
 *
 * <p>Un {@code record} et non l'entite de trace : la sonde n'ecrit rien, et aucune entite
 * JPA ne franchit la frontiere HTTP.
 *
 * @param ok le relais a accepte un message d'essai
 * @param cause pourquoi, quand ce n'est pas le cas
 * @param detail complement lisible, jamais la reponse brute du relais ni le mot de passe
 */
public record DiagnosticNotification(boolean ok, CauseNotification cause, String detail) {
}

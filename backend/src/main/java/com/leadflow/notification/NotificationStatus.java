package com.leadflow.notification;

/**
 * Ce qu'une tentative de notification a donne.
 *
 * <p>{@code IGNOREE} n'est pas un echec : c'est un lead sous le seuil de sa boutique, donc
 * une decision, et elle merite une trace au meme titre qu'un envoi. Sans elle, un silence
 * ressemblerait a une panne.
 */
public enum NotificationStatus {
    ENVOYEE,
    ECHEC,
    IGNOREE
}

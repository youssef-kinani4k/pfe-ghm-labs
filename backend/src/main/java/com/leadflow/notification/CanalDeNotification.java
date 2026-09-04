package com.leadflow.notification;

import com.leadflow.notification.model.NotificationLead;

/**
 * Port de sortie vers un canal d'alerte.
 *
 * <p>Une implementation par canal, annotee {@code @Component} et resolue par
 * {@link CanalDeNotificationRegistry} — jamais par un {@code switch}. Ajouter un canal, c'est
 * ecrire une classe : aucun autre package n'a a etre modifie.
 */
public interface CanalDeNotification {

    /**
     * Identifiant stable du canal, ecrit tel quel dans {@code notification_attempt.channel}.
     * Il est porte par la trace pour que changer de canal ne rende pas l'historique illisible.
     */
    String identifiant();

    /**
     * Previent le commercial.
     *
     * @throws NotificationException si le canal a echoue pour une raison qu'une nouvelle
     *     tentative peut reparer — le message repart alors vers la DLQ apres trois essais.
     *     Les causes deterministes (commercial sans adresse, lead sans commercial) ne
     *     passent jamais par ici : le service les tranche avant.
     */
    void envoie(NotificationLead lead);
}

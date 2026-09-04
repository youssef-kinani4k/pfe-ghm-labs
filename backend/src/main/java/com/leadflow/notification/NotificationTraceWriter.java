package com.leadflow.notification;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit une ligne de {@code notification_attempt} dans sa propre transaction.
 *
 * <p>{@code REQUIRES_NEW} n'est pas decoratif : c'est ce qui fait qu'une trace d'echec
 * survit a l'exception qui part ensuite vers la DLQ. Meme parti que
 * {@code LeadActionJournal}, qui journalise un rejeu rate <b>avant</b> de laisser partir son
 * echec — sans quoi la seule trace de l'incident disparaitrait avec lui.
 *
 * <p>La classe et sa methode ne sont pas {@code final} : {@code NotificationServiceTest} en
 * derive un double en memoire pour eprouver les decisions du service sans base.
 */
@Component
public class NotificationTraceWriter {

    private final NotificationAttemptRepository tentatives;

    public NotificationTraceWriter(NotificationAttemptRepository tentatives) {
        this.tentatives = tentatives;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ecrit(
            UUID leadId,
            UUID salesRepId,
            String canal,
            String destinataire,
            NotificationStatus statut,
            int score,
            int seuil,
            String erreur) {
        NotificationAttempt tentative = new NotificationAttempt();
        tentative.setLeadId(leadId);
        tentative.setSalesRepId(salesRepId);
        tentative.setChannel(canal);
        // La colonne est NOT NULL : un refus survenu avant qu'on connaisse le destinataire
        // ecrit une chaine vide plutot que de faire echouer la trace elle-meme.
        tentative.setRecipient(destinataire == null ? "" : destinataire);
        tentative.setStatus(statut);
        tentative.setScore(score);
        tentative.setSeuil(seuil);
        tentative.setErrorMessage(erreur);
        tentatives.save(tentative);
    }
}

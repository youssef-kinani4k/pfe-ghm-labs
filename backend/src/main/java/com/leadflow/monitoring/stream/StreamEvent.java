package com.leadflow.monitoring.stream;

import java.time.Instant;
import java.util.UUID;

/**
 * Charge utile volontairement maigre : l'identifiant et l'etat suffisent a animer une ligne,
 * le detail se charge au clic. Une connexion longue ne doit pas diffuser en continu des
 * e-mails et des messages de prospects.
 */
public record StreamEvent(
        UUID leadId, UUID clientId, String status, Integer score, UUID salesRepId,
        Instant occurredAt) {
}

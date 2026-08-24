package com.leadflow.monitoring.dto;

import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Ligne du journal telle que l'ecran la lit. {@code replayWarning} est calcule cote serveur
 * plutot que devine cote client : c'est une consequence d'une decision de F4 — le tour de
 * role n'est pas idempotent — et elle appartient au backend.
 */
public record DeadLetterView(
        UUID id,
        String originQueue,
        String routingKey,
        UUID clientId,
        String clientName,
        UUID leadId,
        String failureReason,
        Instant deadAt,
        DeadLetterStatus status,
        Instant replayedAt,
        String replayedBy,
        String payload,
        String replayWarning) {
}

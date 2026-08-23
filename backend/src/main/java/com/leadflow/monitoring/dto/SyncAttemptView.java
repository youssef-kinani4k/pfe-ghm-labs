package com.leadflow.monitoring.dto;

import com.leadflow.crm.CrmSyncAttemptStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne de la trace append-only de F5. Les quatre references y figurent : c'est ce qui
 * permet de lire ou une synchronisation s'est arretee — tiers cree mais pas l'opportunite,
 * par exemple — sans ouvrir la base.
 */
public record SyncAttemptView(
        UUID id,
        String providerId,
        CrmSyncAttemptStatus status,
        String accountRef,
        String contactRef,
        String opportunityRef,
        String taskRef,
        String errorMessage,
        Instant attemptedAt) {
}

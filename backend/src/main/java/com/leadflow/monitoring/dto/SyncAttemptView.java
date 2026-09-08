package com.leadflow.monitoring.dto;

import com.leadflow.crm.CrmSyncAttemptNature;
import com.leadflow.crm.CrmSyncAttemptStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne de la trace append-only de F5. Depuis F15 elle porte sa {@code nature} et la
 * reference du responsable.
 *
 * <p>{@code taskRef} n'y figure plus : la colonne existe depuis {@code V2}, aucun adaptateur
 * ne l'a jamais remplie, et l'ecran affichait « tache — » sur chaque tentative de chaque lead
 * depuis F5 tout en taisant {@code assigneeRef}, la seule reference qui ait bouge depuis. La
 * colonne de base reste : elle ne coute rien, et un adaptateur pourra la remplir.
 */
public record SyncAttemptView(
        UUID id,
        String providerId,
        CrmSyncAttemptStatus status,
        CrmSyncAttemptNature nature,
        String accountRef,
        String contactRef,
        String opportunityRef,
        String assigneeRef,
        String errorMessage,
        Instant attemptedAt) {
}

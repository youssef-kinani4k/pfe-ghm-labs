package com.leadflow.monitoring.dto;

import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tout ce qu'on sait d'un lead, assemble depuis les traces des trois etapes qui l'ont
 * touche : la qualification pour la ligne elle-meme, la capture pour l'evenement d'origine,
 * la synchronisation pour l'historique ERP.
 *
 * <p>{@code phone} et {@code message} sont ici alors que {@code LeadSummary} ne les porte
 * pas : une liste n'a pas besoin du message d'un prospect, un ecran de diagnostic si.
 */
public record LeadDetail(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        UUID clientId,
        String clientName,
        String companyName,
        String firstName,
        String lastName,
        String email,
        String phone,
        String message,
        String detectedIntent,
        IntentSource intentSource,
        int score,
        LeadStatus status,
        String countryCode,
        String sector,
        SalesRepView salesRep,
        List<SyncAttemptView> syncAttempts,
        RawEventView rawEvent,
        boolean chaud) {
}

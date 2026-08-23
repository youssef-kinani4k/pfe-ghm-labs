package com.leadflow.monitoring.dto;

import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Ligne de la liste des leads. Jamais l'entite : {@code Lead} porte le message brut du
 * prospect, et surtout la barriere doit etre systematique pour tenir — un DTO ici, une
 * entite la, et la regle ne protege plus rien.
 */
public record LeadSummary(
        UUID id,
        Instant createdAt,
        UUID clientId,
        String clientName,
        String companyName,
        String email,
        String detectedIntent,
        IntentSource intentSource,
        int score,
        LeadStatus status,
        UUID assignedSalesRepId,
        String salesRepName,
        String countryCode,
        String sector) {
}

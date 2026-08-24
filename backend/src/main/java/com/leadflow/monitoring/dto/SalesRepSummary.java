package com.leadflow.monitoring.dto;

import java.util.UUID;

/**
 * Un commercial dans l'annuaire. {@code active} en fait partie : un commercial desactive
 * explique souvent un tour de role desequilibre, et le taire rendrait l'ecran menteur.
 */
public record SalesRepSummary(
        UUID id,
        String fullName,
        String email,
        String sector,
        String zone,
        boolean active,
        String crmRef) {
}

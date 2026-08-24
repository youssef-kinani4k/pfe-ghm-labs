package com.leadflow.monitoring.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.UUID;

/**
 * Le strict necessaire pour alimenter un filtre. Ecrit a la main plutot que derive de
 * l'entite : {@code Client} porte {@code hmacSecret} et {@code crmConfig} dechiffres a la
 * lecture par les converters, et le seul moyen sur de ne jamais les publier est de ne
 * jamais serialiser l'entite. Ce record est la barriere.
 */
public record ClientSummary(
        UUID id,
        String name,
        boolean active,
        String crmProviderId,
        AssignmentStrategyType assignmentStrategy) {
}

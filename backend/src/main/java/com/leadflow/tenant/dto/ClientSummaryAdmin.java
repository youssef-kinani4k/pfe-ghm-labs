package com.leadflow.tenant.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.UUID;

/** Ligne de la liste des boutiques. Aucun reglage ERP : la fiche s'en charge. */
public record ClientSummaryAdmin(
        UUID id,
        String name,
        String crmProviderId,
        AssignmentStrategyType assignmentStrategy,
        boolean active,
        long activeSalesReps) {
}

package com.leadflow.tenant.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Corps de creation et de mise a jour.
 *
 * <p>{@code firstSalesRep} n'est exige qu'a la creation — la mise a jour reutilise ce record
 * en l'ignorant. C'est la contrepartie assumee d'un seul type de formulaire.
 */
public record ClientForm(
        @NotBlank String name,
        @NotBlank String crmProviderId,
        @NotNull AssignmentStrategyType assignmentStrategy,
        @NotNull Map<String, String> crmSettings,
        @Valid SalesRepForm firstSalesRep) {
}

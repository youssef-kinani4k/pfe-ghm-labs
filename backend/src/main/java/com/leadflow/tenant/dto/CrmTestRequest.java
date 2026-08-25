package com.leadflow.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Parametres a eprouver, non enregistres : tout l'interet est de verifier avant de sauver. */
public record CrmTestRequest(
        @NotBlank String crmProviderId, @NotNull Map<String, String> crmSettings) {
}

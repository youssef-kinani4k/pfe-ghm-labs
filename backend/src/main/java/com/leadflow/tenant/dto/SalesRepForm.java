package com.leadflow.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SalesRepForm(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        String sector,
        String zone,
        String crmRef) {
}

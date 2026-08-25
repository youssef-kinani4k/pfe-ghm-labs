package com.leadflow.tenant.dto;

import java.util.UUID;

public record SalesRepAdminView(
        UUID id,
        String fullName,
        String email,
        String sector,
        String zone,
        String crmRef,
        boolean active) {
}

package com.leadflow.monitoring.dto;

import java.util.UUID;

/**
 * Le commercial attribue, tel que l'ecran de detail le montre. {@code crmRef} en fait
 * partie : c'est la reference qui explique, quand une synchronisation echoue, si le
 * commercial etait resolu dans l'ERP ou non.
 */
public record SalesRepView(
        UUID id, String fullName, String email, String sector, String zone, String crmRef) {
}

package com.leadflow.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

public record ConnectorClientActivity(
        UUID clientId,
        String clientName,
        long successCount,
        long failureCount,
        Instant lastAttemptAt) {
}

package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordonnees de l'API REST Dolibarr (etape 3 : synchronisation ERP).
 */
@ConfigurationProperties(prefix = "leadflow.dolibarr")
public record DolibarrProperties(
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout) {
}

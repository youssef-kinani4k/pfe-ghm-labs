package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres de verification des webhooks entrants (etape 1 : capture securisee).
 */
@ConfigurationProperties(prefix = "leadflow.webhook")
public record WebhookProperties(
        String hmacSecret,
        String signatureHeader,
        Duration tolerance) {
}

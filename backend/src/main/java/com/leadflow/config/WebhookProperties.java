package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres de verification des webhooks entrants. Le secret de signature n'est plus ici :
 * chaque client a le sien, porte par {@code client.hmac_secret}.
 */
@ConfigurationProperties(prefix = "leadflow.webhook")
public record WebhookProperties(
        String signatureHeader,
        Duration tolerance) {
}

package com.leadflow.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration des ERP/CRM cibles. Chaque fournisseur est une entree de
 * {@code leadflow.crm.providers}, ce qui permet d'en ajouter un sans toucher au code de
 * configuration.
 *
 * <p>Tous les champs de {@link Provider} ne concernent pas tous les ERP : {@code apiKey}
 * suffit a Dolibarr, tandis qu'Odoo exige en plus {@code database} et {@code username}.
 * Chaque adaptateur valide ce dont il a besoin a son demarrage.
 */
@ConfigurationProperties(prefix = "leadflow.crm")
public record CrmProperties(
        String defaultProvider,
        Map<String, Provider> providers) {

    public record Provider(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String database,
            String username,
            String password,
            Duration connectTimeout,
            Duration readTimeout) {
    }
}

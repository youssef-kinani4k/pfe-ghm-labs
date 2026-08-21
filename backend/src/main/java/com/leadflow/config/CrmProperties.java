package com.leadflow.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages techniques des adaptateurs ERP. Tout ce qui depend du client — URL de
 * l'instance, cle d'API, base, utilisateur — vit desormais sur la ligne {@code client},
 * dans son document {@code crm_config}. Ne restent ici que les reglages communs a toutes
 * les instances d'un meme fournisseur.
 */
@ConfigurationProperties(prefix = "leadflow.crm")
public record CrmProperties(Map<String, Provider> providers) {

    public record Provider(
            boolean enabled,
            Duration connectTimeout,
            Duration readTimeout) {
    }
}

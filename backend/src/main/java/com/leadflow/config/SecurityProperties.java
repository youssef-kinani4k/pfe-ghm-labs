package com.leadflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cle maitre de chiffrement des secrets stockes en base. Elle n'est jamais persistee :
 * elle vient de l'environnement, et sa perte rend les secrets irrecuperables.
 */
@ConfigurationProperties(prefix = "leadflow.security")
public record SecurityProperties(String masterKey) {
}

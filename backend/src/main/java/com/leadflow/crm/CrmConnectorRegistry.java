package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resout l'adaptateur a utiliser pour un lead donne. Le pipeline appelle
 * {@link #forProvider(String)} avec le fournisseur configure pour le client concerne,
 * ou {@link #defaultConnector()} en l'absence de choix explicite.
 */
@Component
public class CrmConnectorRegistry {

    private final Map<String, CrmConnector> connectors;
    private final String defaultProvider;

    public CrmConnectorRegistry(List<CrmConnector> connectors, CrmProperties properties) {
        this.connectors = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(CrmConnector::providerId, Function.identity()));
        this.defaultProvider = properties.defaultProvider();
    }

    public CrmConnector forProvider(String providerId) {
        CrmConnector connector = connectors.get(providerId);
        if (connector == null) {
            throw new IllegalArgumentException(
                    "Aucun connecteur CRM pour '" + providerId + "'. Disponibles : " + connectors.keySet());
        }
        return connector;
    }

    public CrmConnector defaultConnector() {
        return forProvider(defaultProvider);
    }

    /** Fournisseurs effectivement disponibles au runtime, pour l'ecran Connecteurs. */
    public java.util.Set<String> availableProviders() {
        return connectors.keySet();
    }
}

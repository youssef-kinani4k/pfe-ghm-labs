package com.leadflow.crm;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resout l'adaptateur a utiliser pour un lead donne.
 *
 * <p>Le pipeline appelle {@link #forProvider(String)} avec le fournisseur configure sur le
 * client concerne. Il n'y a pas de connecteur par defaut : tout lead appartient a un
 * client, et tout client nomme son fournisseur.
 */
@Component
public class CrmConnectorRegistry {

    private final Map<String, CrmConnector> connectors;

    public CrmConnectorRegistry(List<CrmConnector> connectors) {
        this.connectors = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(CrmConnector::providerId, Function.identity()));
    }

    public CrmConnector forProvider(String providerId) {
        CrmConnector connector = connectors.get(providerId);
        if (connector == null) {
            throw new IllegalArgumentException(
                    "Aucun connecteur CRM pour '" + providerId + "'. Disponibles : " + connectors.keySet());
        }
        return connector;
    }

    /** Fournisseurs effectivement disponibles au runtime, pour l'ecran Connecteurs. */
    public Set<String> availableProviders() {
        return connectors.keySet();
    }
}

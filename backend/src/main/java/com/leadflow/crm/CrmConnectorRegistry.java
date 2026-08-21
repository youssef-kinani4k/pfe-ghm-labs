package com.leadflow.crm;

import com.leadflow.config.CrmProperties;
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
 * client concerne. Il n'y a pas de connecteur par defaut : tout lead appartient a un client,
 * et tout client nomme son fournisseur.
 *
 * <p>Un adaptateur present dans le classpath mais desactive par configuration est refuse
 * avec un message distinct de celui d'un fournisseur inconnu : les deux situations se
 * corrigent a des endroits differents.
 */
@Component
public class CrmConnectorRegistry {

    private final Map<String, CrmConnector> connectors;
    private final Set<String> enabled;

    public CrmConnectorRegistry(List<CrmConnector> connectors, CrmProperties properties) {
        this.connectors = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(CrmConnector::providerId, Function.identity()));
        Map<String, CrmProperties.Provider> declares =
                properties.providers() == null ? Map.of() : properties.providers();
        this.enabled = declares.entrySet().stream()
                .filter(entree -> entree.getValue().enabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public CrmConnector forProvider(String providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException(
                    "Aucun fournisseur CRM demande : providerId est null. Disponibles : "
                            + availableProviders());
        }
        CrmConnector connector = connectors.get(providerId);
        if (connector == null) {
            throw new IllegalArgumentException(
                    "Aucun connecteur CRM pour '" + providerId + "'. Disponibles : "
                            + availableProviders());
        }
        if (!enabled.contains(providerId)) {
            throw new IllegalArgumentException(
                    "Le connecteur CRM '" + providerId + "' est desactive : "
                            + "leadflow.crm.providers." + providerId + ".enabled=false");
        }
        return connector;
    }

    /** Fournisseurs implementes ET actives, pour l'ecran Connecteurs du dashboard. */
    public Set<String> availableProviders() {
        return connectors.keySet().stream()
                .filter(enabled::contains)
                .collect(Collectors.toUnmodifiableSet());
    }
}

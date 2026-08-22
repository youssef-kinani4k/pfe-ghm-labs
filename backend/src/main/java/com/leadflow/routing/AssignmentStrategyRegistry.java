package com.leadflow.routing;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resout la strategie d'attribution declaree par le client.
 *
 * <p>Meme motif que {@code CrmConnectorRegistry} : les strategies sont collectees par
 * injection de {@code List<AssignmentStrategy>}, donc il n'y a aucune liste a maintenir a
 * la main et aucun {@code switch} sur le type quelque part.
 *
 * <p>Le constructeur echoue si un type est revendique deux fois ou n'a aucun titulaire. Une
 * erreur de cablage doit arreter le demarrage, pas surgir au premier lead d'un client dont
 * la strategie n'a jamais ete implementee.
 */
@Component
public class AssignmentStrategyRegistry {

    private final Map<AssignmentStrategyType, AssignmentStrategy> parType =
            new EnumMap<>(AssignmentStrategyType.class);

    public AssignmentStrategyRegistry(List<AssignmentStrategy> strategies) {
        for (AssignmentStrategy strategie : strategies) {
            AssignmentStrategy ancienne = parType.put(strategie.type(), strategie);
            if (ancienne != null) {
                throw new IllegalStateException(
                        "Deux strategies revendiquent le type " + strategie.type());
            }
        }
        for (AssignmentStrategyType type : AssignmentStrategyType.values()) {
            if (!parType.containsKey(type)) {
                throw new IllegalStateException("Aucune strategie pour le type " + type);
            }
        }
    }

    public AssignmentStrategy pour(AssignmentStrategyType type) {
        AssignmentStrategy strategie = parType.get(type);
        if (strategie == null) {
            throw new IllegalStateException("Aucune strategie pour le type " + type);
        }
        return strategie;
    }
}

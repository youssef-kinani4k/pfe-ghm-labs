package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tour de role pur : le commercial servi le plus anciennement prend le lead suivant.
 *
 * <p>Le corps tient en une ligne parce que tout le travail est fait en amont par
 * {@link RotationOrder}. Les deux autres strategies s'appuient sur celle-ci pour departager
 * leurs candidats, ce qui evite que le premier commercial d'un secteur prenne tout.
 */
@Component
public class RoundRobinStrategy implements AssignmentStrategy {

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.ROUND_ROBIN;
    }

    @Override
    public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
        return eligibles.stream().findFirst();
    }
}

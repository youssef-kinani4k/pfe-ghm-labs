package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Attribution par secteur d'activite : {@code sales_rep.sector} contre {@code lead.sector}.
 *
 * <p>La correspondance est celle de {@link CritereTextuel} : <b>exacte</b>, casse et espaces
 * mis a part. « industrie » ne reconnait donc pas « industrie du textile ». Meme regle que
 * le ciblage sectoriel du bareme de scoring de F3.
 *
 * <p>Le departage entre plusieurs commerciaux du meme secteur est delegue au round-robin.
 */
@Component
public class SectorStrategy implements AssignmentStrategy {

    private static final Logger log = LoggerFactory.getLogger(SectorStrategy.class);

    private final RoundRobinStrategy rotation;

    public SectorStrategy(RoundRobinStrategy rotation) {
        this.rotation = rotation;
    }

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.SECTOR;
    }

    @Override
    public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
        List<SalesRep> duSecteur = eligibles.stream()
                .filter(commercial ->
                        CritereTextuel.correspond(commercial.getSector(), lead.getSector()))
                .toList();
        if (duSecteur.isEmpty()) {
            log.info("Aucun secteur ne correspond a « {} » pour le lead {} : repli sur le tour"
                    + " de role", lead.getSector(), lead.getId());
            return rotation.choisit(lead, eligibles);
        }
        return rotation.choisit(lead, duSecteur);
    }
}

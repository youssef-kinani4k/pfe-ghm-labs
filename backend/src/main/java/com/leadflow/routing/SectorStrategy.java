package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Attribution par secteur d'activite : {@code sales_rep.sector} contre {@code lead.sector}.
 *
 * <p>La correspondance est <b>exacte</b>, casse et espaces mis a part : « industrie » ne
 * reconnait pas « industrie du textile ». Une comparaison par prefixe ferait d'un secteur
 * court un piege silencieux qui capterait tout ce qui commence pareil. Meme regle que le
 * ciblage sectoriel du bareme de scoring de F3.
 *
 * <p>Le departage entre plusieurs commerciaux du meme secteur est delegue au round-robin.
 */
@Component
public class SectorStrategy implements AssignmentStrategy {

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
                        GeographicStrategy.correspond(commercial.getSector(), lead.getSector()))
                .toList();
        return rotation.choisit(lead, duSecteur.isEmpty() ? eligibles : duSecteur);
    }
}

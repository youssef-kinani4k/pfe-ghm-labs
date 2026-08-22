package com.leadflow.routing;

import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.SalesRep;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Ordonne les commerciaux du moins recemment servi au plus recemment servi.
 *
 * <p><b>Aucun compteur.</b> Le tour se lit dans la table {@code lead} : un curseur persiste
 * demanderait une migration, un verrou pour deux livraisons concurrentes, et pourrait
 * pointer vers un commercial desactive entre-temps. L'ordre deduit ne peut pas diverger de
 * la realite — il <i>est</i> la realite. Un commercial ajoute aujourd'hui n'a aucune
 * attribution, donc il passe en tete, ce qui est le comportement voulu.
 *
 * <p>Seule classe du package a toucher la base : les strategies restent des fonctions pures
 * sur la liste qu'elle rend, et se testent sans Spring.
 */
@Component
public class RotationOrder {

    private final LeadRepository leadRepository;

    public RotationOrder(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    public List<SalesRep> parAnciennete(UUID clientId, List<SalesRep> commerciaux) {
        if (commerciaux.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instant> dernieres =
                leadRepository.derniereAttributionParCommercial(clientId).stream()
                        .collect(Collectors.toMap(
                                AttributionRecente::getSalesRepId,
                                AttributionRecente::getDerniereAttribution));

        // nullsFirst : jamais servi passe avant tout le monde. Le departage par
        // identifiant rend l'ordre total, donc l'attribution reproductible a donnees egales.
        return commerciaux.stream()
                .sorted(Comparator
                        .comparing((SalesRep commercial) -> dernieres.get(commercial.getId()),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SalesRep::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}

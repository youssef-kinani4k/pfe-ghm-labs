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
 * Attribution par territoire : {@code sales_rep.zone} contre {@code lead.country_code}.
 *
 * <p><b>Ce que compare vraiment cette strategie.</b> {@code zone} est un texte libre saisi
 * par l'agence, {@code country_code} un code ISO a deux lettres produit par la
 * normalisation de F3. La correspondance n'a donc lieu que si l'agence remplit {@code zone}
 * avec des codes pays : en pratique, c'est une strategie « par code pays ». Le repli couvre
 * le cas contraire, et renommer la colonne demanderait une migration.
 *
 * <p>Le departage entre plusieurs commerciaux du meme territoire est delegue au round-robin
 * — sans quoi le premier de la liste prendrait tout.
 *
 * <p>C'est la strategie elle-meme qui journalise son repli, et non l'orchestrateur : elle
 * seule sait que son filtre est revenu vide, et le lui faire redeviner imposerait un
 * {@code switch} sur le type de strategie que le registre existe justement pour eviter.
 */
@Component
public class GeographicStrategy implements AssignmentStrategy {

    private static final Logger log = LoggerFactory.getLogger(GeographicStrategy.class);

    private final RoundRobinStrategy rotation;

    public GeographicStrategy(RoundRobinStrategy rotation) {
        this.rotation = rotation;
    }

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.GEOGRAPHIC;
    }

    @Override
    public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
        List<SalesRep> duTerritoire = eligibles.stream()
                .filter(commercial ->
                        CritereTextuel.correspond(commercial.getZone(), lead.getCountryCode()))
                .toList();
        if (duTerritoire.isEmpty()) {
            // On rend la main au tour de role plutot que de laisser le lead en souffrance :
            // un prospect qui attend coute plus cher qu'une attribution imparfaite. Le log
            // existe pour que la configuration incomplete du client se voie.
            log.info("Aucune zone ne correspond au pays {} du lead {} : repli sur le tour de role",
                    lead.getCountryCode(), lead.getId());
            return rotation.choisit(lead, eligibles);
        }
        return rotation.choisit(lead, duTerritoire);
    }
}

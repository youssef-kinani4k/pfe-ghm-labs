package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 */
@Component
public class GeographicStrategy implements AssignmentStrategy {

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
                .filter(commercial -> correspond(commercial.getZone(), lead.getCountryCode()))
                .toList();
        // Filtre vide : on rend la main au tour de role plutot que de laisser le lead en
        // souffrance. Le service logue ce repli.
        return rotation.choisit(lead, duTerritoire.isEmpty() ? eligibles : duTerritoire);
    }

    /** @return {@code false} des qu'une des deux valeurs manque : rien ne peut correspondre */
    static boolean correspond(String valeur, String attendue) {
        if (valeur == null || attendue == null) {
            return false;
        }
        return valeur.trim().toLowerCase(Locale.ROOT)
                .equals(attendue.trim().toLowerCase(Locale.ROOT));
    }
}

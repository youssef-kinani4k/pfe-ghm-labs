package com.leadflow.routing;

import static com.leadflow.routing.RoundRobinStrategyTest.commercial;
import static com.leadflow.routing.RoundRobinStrategyTest.lead;
import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeographicStrategyTest {

    private final GeographicStrategy strategie = new GeographicStrategy(new RoundRobinStrategy());

    @Test
    void seDeclareSousLeTypeGeographique() {
        assertThat(strategie.type()).isEqualTo(AssignmentStrategyType.GEOGRAPHIC);
    }

    @Test
    void retientLeCommercialDeLaZoneDuLead() {
        SalesRep maroc = commercial("ma@demo.test", null, "MA");
        SalesRep france = commercial("fr@demo.test", null, "FR");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(france, maroc)))
                .contains(maroc);
    }

    /** La zone est saisie a la main : « ma » et « MA » designent le meme territoire. */
    @Test
    void ignoreLaCasseEtLesEspacesDeLaZone() {
        SalesRep maroc = commercial("ma@demo.test", null, "  ma ");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(maroc))).contains(maroc);
    }

    /**
     * Parmi plusieurs commerciaux de la meme zone, c'est l'ordre de rotation qui tranche :
     * la liste arrive triee, on prend la tete.
     */
    @Test
    void departageDeuxCommerciauxDeLaMemeZoneParLaRotation() {
        SalesRep premier = commercial("premier@demo.test", null, "MA");
        SalesRep second = commercial("second@demo.test", null, "MA");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(premier, second)))
                .contains(premier);
    }

    /**
     * Repli : un prospect qui attend coute plus cher qu'une attribution imparfaite. Le
     * service logue le repli pour que la configuration incomplete du client se voie.
     */
    @Test
    void repliSurLaRotationQuandAucuneZoneNeCorrespond() {
        SalesRep france = commercial("fr@demo.test", null, "FR");
        SalesRep espagne = commercial("es@demo.test", null, "ES");

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(france, espagne)))
                .contains(france);
    }

    @Test
    void repliSurLaRotationQuandLeLeadNAPasDePays() {
        SalesRep france = commercial("fr@demo.test", null, "FR");

        assertThat(strategie.choisit(lead(null, "industrie"), List.of(france))).contains(france);
    }

    @Test
    void neChoisitPersonneQuandLaListeEstVide() {
        assertThat(strategie.choisit(lead("MA", "industrie"), List.of())).isEmpty();
    }
}

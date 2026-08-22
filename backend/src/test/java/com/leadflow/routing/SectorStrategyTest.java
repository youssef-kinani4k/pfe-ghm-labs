package com.leadflow.routing;

import static com.leadflow.routing.RoundRobinStrategyTest.commercial;
import static com.leadflow.routing.RoundRobinStrategyTest.lead;
import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectorStrategyTest {

    private final SectorStrategy strategie = new SectorStrategy(new RoundRobinStrategy());

    @Test
    void seDeclareSousLeTypeSectoriel() {
        assertThat(strategie.type()).isEqualTo(AssignmentStrategyType.SECTOR);
    }

    @Test
    void retientLeCommercialDuSecteurDuLead() {
        SalesRep industrie = commercial("indus@demo.test", "industrie", null);
        SalesRep services = commercial("services@demo.test", "services", null);

        assertThat(strategie.choisit(lead("MA", "Industrie"), List.of(services, industrie)))
                .contains(industrie);
    }

    /**
     * Correspondance exacte, jamais partielle : « industrie » ne reconnait pas « industrie
     * du textile ». Un client qui veut les deux enumere deux commerciaux — une comparaison
     * par prefixe ferait de « industrie » un piege silencieux.
     */
    @Test
    void neReconnaitPasUnSecteurSeulementPrefixe() {
        SalesRep industrie = commercial("indus@demo.test", "industrie", null);
        SalesRep autre = commercial("autre@demo.test", "services", null);

        // Aucun secteur ne correspond exactement : repli sur la rotation, donc le premier.
        assertThat(strategie.choisit(lead("MA", "industrie du textile"), List.of(autre, industrie)))
                .contains(autre);
    }

    @Test
    void repliSurLaRotationQuandAucunSecteurNeCorrespond() {
        SalesRep services = commercial("services@demo.test", "services", null);

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(services)))
                .contains(services);
    }

    @Test
    void repliSurLaRotationQuandLeLeadNAPasDeSecteur() {
        SalesRep services = commercial("services@demo.test", "services", null);

        assertThat(strategie.choisit(lead("MA", null), List.of(services))).contains(services);
    }

    @Test
    void neChoisitPersonneQuandLaListeEstVide() {
        assertThat(strategie.choisit(lead("MA", "industrie"), List.of())).isEmpty();
    }
}

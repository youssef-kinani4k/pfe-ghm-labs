package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoundRobinStrategyTest {

    private final RoundRobinStrategy strategie = new RoundRobinStrategy();

    static SalesRep commercial(String email, String secteur, String zone) {
        SalesRep commercial = new SalesRep();
        commercial.setFullName("Commercial " + email);
        commercial.setEmail(email);
        commercial.setSector(secteur);
        commercial.setZone(zone);
        return commercial;
    }

    static Lead lead(String pays, String secteur) {
        Lead lead = new Lead();
        lead.setEmail("prospect@acme.test");
        lead.setCountryCode(pays);
        lead.setSector(secteur);
        return lead;
    }

    @Test
    void seDeclareSousLeTypeRoundRobin() {
        assertThat(strategie.type()).isEqualTo(AssignmentStrategyType.ROUND_ROBIN);
    }

    /**
     * La liste arrive deja triee du moins recemment servi au plus recemment servi : la
     * strategie n'a qu'a prendre la tete. C'est ce qui la rend testable sans base.
     */
    @Test
    void prendLePremierDeLaListeOrdonnee() {
        SalesRep premier = commercial("premier@demo.test", null, null);
        SalesRep second = commercial("second@demo.test", null, null);

        assertThat(strategie.choisit(lead("MA", "industrie"), List.of(premier, second)))
                .contains(premier);
    }

    @Test
    void neChoisitPersonneQuandLaListeEstVide() {
        assertThat(strategie.choisit(lead("MA", "industrie"), List.of())).isEmpty();
    }
}

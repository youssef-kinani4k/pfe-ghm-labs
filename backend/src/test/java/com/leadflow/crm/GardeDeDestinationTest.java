package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.CrmProperties;
import com.leadflow.config.SsrfProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Le test qui verrouille l'ancrage. Il n'eprouve pas la regle — la tache 4 s'en charge —
 * mais le fait que le builder rendu par la fabrique la porte. Si quelqu'un retirait
 * l'intercepteur, tous les adaptateurs perdraient le garde en silence.
 */
class GardeDeDestinationTest {

    private CrmHttpConfig fabrique() {
        CrmProperties proprietes = new CrmProperties(Map.of(
                "dolibarr",
                new CrmProperties.Provider(true, Duration.ofSeconds(2), Duration.ofSeconds(2))));
        PolitiqueDeDestination politique =
                new PolitiqueDeDestination(new SsrfProperties(true, List.of()));
        return new CrmHttpConfig(proprietes, new GardeDeDestination(politique));
    }

    @Test
    void leBuilderDeLaFabriqueRefuseUneDestinationInterne() {
        RestClient client = fabrique().builderPour("dolibarr").build();

        assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:9/api").retrieve().toBodilessEntity())
                .isInstanceOf(DestinationRefuseeException.class);
    }

    @Test
    void leBuilderDeLaFabriqueRefuseUnSchemaInterdit() {
        RestClient client = fabrique().builderPour("dolibarr").build();

        assertThatThrownBy(() -> client.get().uri("file:///etc/passwd").retrieve().toBodilessEntity())
                .isInstanceOf(DestinationRefuseeException.class);
    }
}

package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leadflow.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * La rafale est ramenee a 3 pour que le test soit court. Aucun client n'est cree en base :
 * c'est deliberé — le filtre doit refuser AVANT toute consultation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
    "leadflow.webhook.rate-limit.actif=true",
    "leadflow.webhook.rate-limit.rafale=3",
    "leadflow.webhook.rate-limit.requetes-par-minute=1"
})
class LimiteurDeDebitTest {

    private static final String CORPS = "{\"source\":\"formulaire\",\"email\":\"a@b.test\"}";

    @Autowired private MockMvc mockMvc;

    private int soumet(String cle) throws Exception {
        return mockMvc.perform(post("/api/webhooks/leads/" + cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** Au-dela de la rafale, le webhook rend 429. */
    @Test
    void refuseAuDelaDeLaRafale() throws Exception {
        String cle = "cle-" + UUID.randomUUID();

        assertThat(soumet(cle)).isEqualTo(401);
        assertThat(soumet(cle)).isEqualTo(401);
        assertThat(soumet(cle)).isEqualTo(401);
        assertThat(soumet(cle)).isEqualTo(429);
    }

    /**
     * <b>Le test qui compte.</b> Une cle publique inventee est limitee exactement comme une
     * cle valide, parce que le filtre ne consulte pas la base. Si un jour quelqu'un
     * « optimisait » le filtre en ne comptant que les clients connus, le 429 deviendrait
     * l'oracle que les cinq 401 uniformes existent pour fermer, et ce test tomberait.
     */
    @Test
    void uneCleInconnueEstLimiteeCommeUneConnue() throws Exception {
        String inventee = "totalement-inventee-" + UUID.randomUUID();

        soumet(inventee);
        soumet(inventee);
        soumet(inventee);

        assertThat(soumet(inventee)).isEqualTo(429);
    }

    /** Le refus annonce une attente, sans quoi l'appelant ne peut que marteler. */
    @Test
    void leRefusPorteRetryAfter() throws Exception {
        String cle = "cle-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            soumet(cle);
        }

        MvcResult refus = mockMvc.perform(post("/api/webhooks/leads/" + cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn();

        assertThat(refus.getResponse().getStatus()).isEqualTo(429);
        assertThat(refus.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(refus.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    /** Deux boutiques ne partagent pas de plafond. */
    @Test
    void deuxClesNePartagentPasLeurPlafond() throws Exception {
        String premiere = "cle-" + UUID.randomUUID();
        String seconde = "cle-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            soumet(premiere);
        }

        assertThat(soumet(seconde)).isEqualTo(401);
    }
}

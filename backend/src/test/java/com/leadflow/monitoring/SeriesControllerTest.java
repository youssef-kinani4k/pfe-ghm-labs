package com.leadflow.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserte le <b>corps JSON</b> et non le DTO : c'est le corps qui est le contrat, et un test
 * sur le record ne prouverait pas qu'aucune entite ne franchit la frontiere HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SeriesControllerTest {

    @Autowired private MockMvc mvc;

    @Test
    @WithMockUser
    void rendLesTroisSeriesAuGrainJour() throws Exception {
        mvc.perform(get("/api/stats/series").param("jours", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume.length()").value(7))
                .andExpect(jsonPath("$.delais.length()").value(7))
                .andExpect(jsonPath("$.intentions.length()").value(7))
                // Une date, pas un instant : le point represente une journee entiere.
                .andExpect(jsonPath("$.volume[0].jour").value(
                        org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}")));
    }

    @Test
    @WithMockUser
    void laFenetreParDefautEstDeTrenteJours() throws Exception {
        mvc.perform(get("/api/stats/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume.length()").value(30));
    }

    @Test
    @WithMockUser
    void refuseUneFenetreHorsDesTroisValeurs() throws Exception {
        mvc.perform(get("/api/stats/series").param("jours", "365"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exigeUneAuthentification() throws Exception {
        mvc.perform(get("/api/stats/series")).andExpect(status().isUnauthorized());
    }
}

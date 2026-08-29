package com.leadflow.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La configuration de production : liste vide, donc aucun CorsConfigurationSource
 * enregistre. Sous Nginx le dashboard et l'API partagent l'origine — il n'y a plus de
 * requete cross-origin a autoriser, et la bonne configuration est l'absence de
 * configuration.
 *
 * <p>Aucun @TestPropertySource : la propriete vaut la liste vide par defaut dans
 * application.yml, et la suite de tests ne la surcharge pas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsVideTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void aucunEnTeteCorsNEstRendu() throws Exception {
        mockMvc.perform(options("/api/leads")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}

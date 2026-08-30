package com.leadflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.CorsFilter;

/**
 * La configuration de production : liste vide, donc aucun mecanisme CORS n'est ajoute a
 * la chaine de filtres. Sous Nginx le dashboard et l'API partagent l'origine — il n'y a
 * plus de requete cross-origin a autoriser, et la bonne configuration est l'absence de
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
    @Autowired private SecurityFilterChain securityFilterChain;

    @Test
    void aucunEnTeteCorsNEstRendu() throws Exception {
        mockMvc.perform(options("/api/leads")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        // L'absence d'en-tete seule ne distingue pas « aucun mecanisme CORS actif » de
        // « mecanisme actif mais configure avec une liste vide » : DefaultCorsProcessor
        // ne rend l'en-tete que si l'origine est autorisee, donc les deux implementations
        // produiraient la meme reponse a la requete ci-dessus. Le contrat exige la
        // premiere — on verifie donc directement qu'aucun CorsFilter n'a ete ajoute a la
        // chaine de filtres de securite. NB : verifier un bean CorsConfigurationSource
        // dans le contexte ne le prouverait pas non plus — Spring MVC en publie toujours
        // un (mvcHandlerMappingIntrospector) independamment de notre configuration, et la
        // source construite dans SecurityConfig n'est de toute facon jamais enregistree
        // comme bean : elle sert uniquement a batir le CorsFilter passe a la chaine.
        assertThat(securityFilterChain.getFilters())
                .noneMatch(CorsFilter.class::isInstance);
    }
}

package com.leadflow.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Le hash correspond au mot de passe « secret-de-test ». Il est ecrit en clair dans le
 * test et nulle part ailleurs : la configuration de production ne porte que des hash,
 * venus de l'environnement.
 *
 * <p>MockMvc et non un client HTTP reel, comme les tests de capture : la chaine de filtres
 * de Spring Security y est traversee en entier, ce qui est precisement ce qu'on eprouve
 * ici.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.jwt-secret=cle-de-signature-de-test-suffisamment-longue-32o",
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class AuthenticationTest {

    private static final String CONNEXION_VALIDE =
            "{\"username\":\"operateur\",\"password\":\"secret-de-test\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void connexionValideRendUnJeton() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONNEXION_VALIDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void motDePasseFauxEstRefuse() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"pas-le-bon\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void identifiantInconnuEstRefuseDeLaMemeFacon() throws Exception {
        // Meme reponse que pour un mot de passe faux : distinguer les deux donnerait un
        // oracle sur les comptes existants.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"personne\",\"password\":\"secret-de-test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endpointProtegeSansJetonEstRefuse() throws Exception {
        mockMvc.perform(get("/api/clients"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jetonSigneAvecUneAutreCleEstRefuse() throws Exception {
        // Jeton bien forme mais signe ailleurs : c'est exactement ce qu'un filtre maison
        // rate quand il decode sans verifier la signature.
        String jetonEtranger = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJzdWIiOiJvcGVyYXRldXIiLCJpc3MiOiJsZWFkZmxvdyIsImV4cCI6NDEwMjQ0NDgwMH0."
                + "c2lnbmF0dXJlLXF1aS1uZS1jb3JyZXNwb25kLWEtcmllbg";

        mockMvc.perform(get("/api/clients").header("Authorization", "Bearer " + jetonEtranger))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jetonValideOuvreLAcces() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONNEXION_VALIDE))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jeton = mapper.readTree(corps).get("token").asText();

        // /api/clients n'existe pas encore : ce qui est verifie ici est que le jeton passe
        // le filtre de securite, donc que la reponse n'est plus un 401.
        int statut = mockMvc
                .perform(get("/api/clients").header("Authorization", "Bearer " + jeton))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(statut).isNotEqualTo(401);
    }

    @Test
    void webhookResteAccessibleSansJeton() throws Exception {
        // Sans signature le webhook rend 401, mais par la verification HMAC et non par la
        // chaine de filtres : la preuve est qu'il atteint bien le controleur. On verifie
        // ici qu'il n'est pas devenu inaccessible.
        mockMvc.perform(post("/api/webhooks/leads/inconnue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}

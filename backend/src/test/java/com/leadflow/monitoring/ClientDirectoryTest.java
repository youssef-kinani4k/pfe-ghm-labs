package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Porte le critere de recette 5 : aucune reponse de l'API ne contient de secret client.
 *
 * <p>L'assertion se fait sur le <b>corps JSON</b> et non sur le DTO. C'est la serialisation
 * qu'on verrouille : un record de sortie correct ne prouve rien si quelqu'un renvoie un jour
 * l'entite, et {@code Client} porte {@code hmacSecret} et {@code crmConfig} que les
 * {@code AttributeConverter} dechiffrent a la lecture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ClientDirectoryTest {

    private static final String SECRET_EN_CLAIR = "secret-hmac-tres-reconnaissable";
    private static final String URL_ERP_EN_CLAIR = "http://erp-prive-du-client.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @AfterEach
    void nettoie() {
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void listeLesClientsSansAucunSecret() throws Exception {
        creeUnClientAvecUnCommercial();

        String corps = mockMvc.perform(get("/api/clients")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(corps)
                .contains("Client de test")
                .doesNotContain(SECRET_EN_CLAIR)
                .doesNotContain(URL_ERP_EN_CLAIR)
                .doesNotContain("hmacSecret")
                .doesNotContain("crmConfig")
                .doesNotContain("scoringConfig");
    }

    @Test
    void rendCeQuIlFautPourAlimenterUnFiltre() throws Exception {
        creeUnClientAvecUnCommercial();

        String corps = mockMvc.perform(get("/api/clients")
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var premier = mapper.readTree(corps).get(0);
        assertThat(premier.get("name").asText()).isEqualTo("Client de test");
        assertThat(premier.get("crmProviderId").asText()).isEqualTo("dolibarr");
        assertThat(premier.get("assignmentStrategy").asText()).isEqualTo("ROUND_ROBIN");
        assertThat(premier.get("active").asBoolean()).isTrue();
    }

    @Test
    void listeLesCommerciauxDUnClientActifsEtInactifs() throws Exception {
        Client client = creeUnClientAvecUnCommercial();

        SalesRep parti = new SalesRep();
        parti.setClient(client);
        parti.setFullName("Ancien Commercial");
        parti.setEmail("ancien@test.fr");
        parti.setActive(false);
        salesRepRepository.saveAndFlush(parti);

        String corps = mockMvc.perform(get("/api/clients/" + client.getId() + "/sales-reps")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Les inactifs sont montres : c'est souvent l'explication d'un tour de role
        // desequilibre, et les cacher rendrait l'ecran menteur.
        assertThat(corps).contains("Camille Durand").contains("Ancien Commercial");
    }

    @Test
    void clientInconnuRend404() throws Exception {
        mockMvc.perform(get("/api/clients/" + UUID.randomUUID() + "/sales-reps")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sansJetonLAnnuaireEstRefuse() throws Exception {
        mockMvc.perform(get("/api/clients"))
                .andExpect(status().isUnauthorized());
    }

    private Client creeUnClientAvecUnCommercial() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret(SECRET_EN_CLAIR);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", URL_ERP_EN_CLAIR, "apiKey", SECRET_EN_CLAIR));
        Client enregistre = clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(enregistre);
        commercial.setFullName("Camille Durand");
        commercial.setEmail("camille@test.fr");
        commercial.setSector("industrie");
        commercial.setZone("FR");
        salesRepRepository.saveAndFlush(commercial);
        return enregistre;
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

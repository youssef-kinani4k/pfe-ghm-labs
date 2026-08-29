package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
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
 * L'assertion porte sur le corps JSON : c'est la serialisation qui sera consommee par
 * l'ecran, et un DTO correct ne prouve pas un JSON correct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ScoringAdminTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;

    private UUID boutique;

    @AfterEach
    void nettoie() {
        clients.deleteAll();
    }

    /**
     * Un document vide ne doit pas rendre un formulaire vide : l'ecran afficherait des zeros
     * la ou le scoreur applique les defauts.
     */
    @Test
    void unDocumentVideRendLesValeursParDefaut() throws Exception {
        boutique = creeBoutique(Map.of());

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = mapper.readTree(corps);
        assertThat(json.get("valeurs").get("telephonePresent").asInt()).isEqualTo(15);
        assertThat(json.get("valeurs").get("intention").get("DEVIS").asInt()).isEqualTo(40);
        assertThat(json.get("valeurs").get("seuilChaud").asInt()).isEqualTo(70);
        assertThat(json.get("scoreMaximum").asInt()).isEqualTo(90);
        assertThat(json.get("seuilInatteignable").asBoolean()).isFalse();
        assertThat(json.get("defauts").get("bonusCible").asInt()).isEqualTo(10);
    }

    @Test
    void ceQuiEstEcritEstReluTelQuel() throws Exception {
        boutique = creeBoutique(Map.of());
        String formulaire = """
                {"telephonePresent":20,"societePresente":12,"nomPresent":6,"messagePresent":8,
                 "intention":{"DEVIS":45,"ACHAT":35,"INFORMATION":10,"SUPPORT":4,"AUTRE":1},
                 "secteursCibles":["Industrie"],"paysCibles":["fr"],
                 "bonusCible":9,"seuilChaud":65}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isOk());

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = mapper.readTree(corps);
        assertThat(json.get("valeurs").get("telephonePresent").asInt()).isEqualTo(20);
        assertThat(json.get("valeurs").get("seuilChaud").asInt()).isEqualTo(65);
        // Normalisation visible : minuscules pour les secteurs, majuscules pour les pays.
        assertThat(json.get("valeurs").get("secteursCibles").get(0).asText())
                .isEqualTo("industrie");
        assertThat(json.get("valeurs").get("paysCibles").get(0).asText()).isEqualTo("FR");
    }

    /** Un document deja en base ecrit de travers donne les defauts, jamais une erreur 500. */
    @Test
    void unDocumentMalformeRendLesDefauts() throws Exception {
        boutique = creeBoutique(Map.of("poids", "ceci n est pas un objet", "seuilChaud", "haut"));

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(mapper.readTree(corps).get("valeurs").get("seuilChaud").asInt())
                .isEqualTo(70);
    }

    private UUID creeBoutique(Map<String, Object> scoring) {
        Client client = new Client();
        client.setName("Boutique de test");
        client.setPublicKey("pk-" + UUID.randomUUID());
        client.setHmacSecret("secret-de-test");
        client.setCrmProviderId("dolibarr");
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setScoringConfig(new java.util.HashMap<>(scoring));
        return clients.save(client).getId();
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

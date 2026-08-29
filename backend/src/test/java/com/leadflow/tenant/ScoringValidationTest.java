package com.leadflow.tenant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
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
 * Les bornes de {@code ScoringForm} viennent du bornage du score : {@code LeadScorer} plafonne
 * le total a 100, donc un poids au-dela n'aurait aucun effet observable. Un seuil au-dessus du
 * maximum atteignable n'est pas dans le meme cas — c'est un etat legitime, signale et non
 * refuse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ScoringValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;

    @AfterEach
    void nettoie() {
        clients.deleteAll();
    }

    /** Au-dela de 100, la valeur serait ineffective : LeadScorer borne le total a 100. */
    @Test
    void unPoidsHorsBornesEstRefuse() throws Exception {
        UUID boutique = creeBoutique();
        String formulaire = """
                {"telephonePresent":150,"societePresente":10,"nomPresent":5,"messagePresent":10,
                 "intention":{},"secteursCibles":[],"paysCibles":[],
                 "bonusCible":10,"seuilChaud":70}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unSeuilNegatifEstRefuse() throws Exception {
        UUID boutique = creeBoutique();
        String formulaire = """
                {"telephonePresent":15,"societePresente":10,"nomPresent":5,"messagePresent":10,
                 "intention":{},"secteursCibles":[],"paysCibles":[],
                 "bonusCible":10,"seuilChaud":-1}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isBadRequest());
    }

    /**
     * Un seuil au-dessus du maximum atteignable n'est pas une erreur : c'est un etat
     * legitime, le temps de monter les poids. Il est accepte, et signale.
     */
    @Test
    void unSeuilInatteignableEstAccepteEtSignale() throws Exception {
        UUID boutique = creeBoutique();
        String formulaire = """
                {"telephonePresent":1,"societePresente":1,"nomPresent":1,"messagePresent":1,
                 "intention":{"DEVIS":5,"ACHAT":5,"INFORMATION":0,"SUPPORT":0,"AUTRE":0},
                 "secteursCibles":[],"paysCibles":[],"bonusCible":0,"seuilChaud":80}""";

        mockMvc.perform(put("/api/admin/clients/" + boutique + "/scoring")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(formulaire))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seuilInatteignable").value(true))
                .andExpect(jsonPath("$.scoreMaximum").value(9));
    }

    @Test
    void uneBoutiqueInconnueRendUn404() throws Exception {
        mockMvc.perform(get("/api/admin/clients/" + UUID.randomUUID() + "/scoring")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isNotFound());
    }

    private UUID creeBoutique() {
        Client client = new Client();
        client.setName("Boutique de test");
        client.setPublicKey("pk-" + UUID.randomUUID());
        client.setHmacSecret("secret-de-test");
        client.setCrmProviderId("dolibarr");
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
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

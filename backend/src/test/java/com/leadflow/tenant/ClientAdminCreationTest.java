package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Les variantes du corps sont des constantes distinctes plutot que le resultat d'un
 * {@code replace} sur {@code CORPS_VALIDE} : un test doit rester lisible, pas malin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ClientAdminCreationTest {

    private static final String CORPS_VALIDE = """
            {
              "name": "Boutique du Centre",
              "crmProviderId": "dolibarr",
              "assignmentStrategy": "ROUND_ROBIN",
              "crmSettings": {"baseUrl": "http://erp.test", "apiKey": "cle-secrete"},
              "firstSalesRep": {"fullName": "Sara Bennani", "email": "sara@test.fr"}
            }
            """;

    private static final String CORPS_FOURNISSEUR_INCONNU = """
            {
              "name": "Boutique du Centre",
              "crmProviderId": "sap",
              "assignmentStrategy": "ROUND_ROBIN",
              "crmSettings": {"baseUrl": "http://erp.test", "apiKey": "cle-secrete"},
              "firstSalesRep": {"fullName": "Sara Bennani", "email": "sara@test.fr"}
            }
            """;

    private static final String CORPS_SANS_CLE_API = """
            {
              "name": "Boutique du Centre",
              "crmProviderId": "dolibarr",
              "assignmentStrategy": "ROUND_ROBIN",
              "crmSettings": {"baseUrl": "http://erp.test"},
              "firstSalesRep": {"fullName": "Sara Bennani", "email": "sara@test.fr"}
            }
            """;

    private static final String CORPS_SANS_COMMERCIAL = """
            {
              "name": "Boutique du Centre",
              "crmProviderId": "dolibarr",
              "assignmentStrategy": "ROUND_ROBIN",
              "crmSettings": {"baseUrl": "http://erp.test", "apiKey": "cle-secrete"}
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    @AfterEach
    void nettoie() {
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @Test
    void creeLaBoutiqueEtRendLeSecretUneSeuleFois() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_VALIDE))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String secret = mapper.readTree(corps).get("hmacSecret").asText();
        String clePublique = mapper.readTree(corps).get("publicKey").asText();
        assertThat(secret).hasSize(64);
        assertThat(mapper.readTree(corps).get("webhookPath").asText())
                .isEqualTo("/api/webhooks/leads/" + clePublique);

        UUID id = UUID.fromString(mapper.readTree(corps).get("id").asText());
        String fiche = mockMvc.perform(get("/api/admin/clients/" + id)
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        // Le secret ne reapparait jamais : perdu veut dire regenere, pas recupere.
        assertThat(fiche).doesNotContain(secret);
        assertThat(fiche).doesNotContain("cle-secrete");
    }

    @Test
    void creeLePremierCommercialDansLaMemeTransaction() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_VALIDE))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(mapper.readTree(corps).get("id").asText());
        assertThat(commerciaux.countByClientIdAndActiveTrue(id)).isEqualTo(1);
    }

    @Test
    void refuseUnFournisseurInconnu() throws Exception {
        mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_FOURNISSEUR_INCONNU))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUnReglageErpManquant() throws Exception {
        mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_SANS_CLE_API))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUneCreationSansCommercial() throws Exception {
        mockMvc.perform(post("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_SANS_COMMERCIAL))
                .andExpect(status().isBadRequest());
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

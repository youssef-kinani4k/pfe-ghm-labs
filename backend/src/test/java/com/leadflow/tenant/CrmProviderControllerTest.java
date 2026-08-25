package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Le formulaire de boutique se genere a partir de ce que le backend declare : ces tests
 * verrouillent le contrat qui rend cette generation possible, et le fait qu'un diagnostic
 * rate ne soit pas presente comme une panne du serveur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class CrmProviderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void listeLesFournisseursEtLeursReglages() throws Exception {
        String corps = mockMvc.perform(get("/api/admin/crm/providers")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Le formulaire se genere a partir de cette reponse : ajouter un ERP ne doit
        // demander aucune modification du frontend.
        assertThat(corps).contains("dolibarr").contains("odoo");
        assertThat(corps).contains("baseUrl").contains("database");
    }

    @Test
    void unTestQuiEchoueRend200AvecSaCause() throws Exception {
        String corps = mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"crmProviderId":"dolibarr",
                                 "crmSettings":{"baseUrl":"http://127.0.0.1:9",
                                                "apiKey":"peu-importe"}}
                                """))
                // Un echec de diagnostic n'est pas une panne du serveur : le traiter en 502
                // ferait passer l'intercepteur du dashboard pour un incident.
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cause").asText()).isEqualTo("INJOIGNABLE");
    }

    @Test
    void unFournisseurInconnuRend400() throws Exception {
        mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"crmProviderId\":\"sap\",\"crmSettings\":{}}"))
                .andExpect(status().isBadRequest());
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

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
        // 127.0.0.1 est une adresse de boucle locale : GardeDeDestination la refuse avant
        // meme la tentative de connexion. Ce n'est donc pas l'ERP qui est injoignable, c'est
        // nous qui avons refuse de l'appeler — la cause doit le dire.
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
        assertThat(mapper.readTree(corps).get("cause").asText()).isEqualTo("DESTINATION_REFUSEE");
    }

    @Test
    void unHoteIntrouvableEstAussiUneDestinationRefusee() throws Exception {
        // Un nom en .invalid est reserve par la RFC 2606 et ne resout jamais : la sonde
        // echoue vite, sans attendre un delai reseau. Ce test devait a l'origine prouver
        // qu'INJOIGNABLE reste atteignable pour une adresse publique injoignable, mais
        // GardeDeDestination refuse aussi les hotes qu'il ne sait pas resoudre (voir
        // PolitiqueDeDestination#verifie, le catch UnknownHostException) : ce chemin rend
        // donc DESTINATION_REFUSEE, pas INJOIGNABLE. Constat garde et assume — voir le
        // rapport pour ce que cela signifie pour la couverture d'INJOIGNABLE.
        String corps = mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"crmProviderId":"dolibarr",
                                 "crmSettings":{"baseUrl":"http://nom-inexistant.invalid",
                                                "apiKey":"peu-importe"}}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cause").asText()).isEqualTo("DESTINATION_REFUSEE");
    }

    @Test
    void unFournisseurInconnuRend400() throws Exception {
        mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"crmProviderId\":\"sap\",\"crmSettings\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void odooAvecDestinationRefuseeRend200AvecSaCause() throws Exception {
        // La garde de destination est partagee entre tous les adaptateurs : le chemin
        // de capture est code-par-code, mais Odoo l'enveloppe differemment (appelle, puis
        // resultat). La sonde ne doit pas supposer, et ce chemin ne doit pas rester couvert
        // seulement par inference depuis le test Dolibarr.
        String corps = mockMvc.perform(post("/api/admin/crm/test")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"crmProviderId":"odoo",
                                 "crmSettings":{"baseUrl":"http://127.0.0.1:9",
                                                "database":"demo",
                                                "username":"admin",
                                                "apiKey":"peu-importe"}}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps).get("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(corps).get("cause").asText()).isEqualTo("DESTINATION_REFUSEE");
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

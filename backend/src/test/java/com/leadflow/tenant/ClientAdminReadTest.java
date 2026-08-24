package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * L'assertion qui compte porte sur le corps JSON et non sur le DTO : c'est la serialisation
 * qui peut fuir, {@code Client} portant {@code hmacSecret} et {@code crmConfig} dechiffres a
 * la lecture par les converters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ClientAdminReadTest {

    private static final String SECRET_EN_CLAIR = "secret-hmac-tres-reconnaissable";
    private static final String CLE_ERP_EN_CLAIR = "cle-api-tres-reconnaissable";

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
    void listeLesBoutiquesAvecLeNombreDeCommerciauxActifs() throws Exception {
        Client boutique = creeUneBoutique("Boutique du Nord");
        creeUnCommercial(boutique, "actif@test.fr", true);
        creeUnCommercial(boutique, "parti@test.fr", false);

        String corps = mockMvc.perform(get("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(corps)).hasSize(1);
        assertThat(mapper.readTree(corps).get(0).get("name").asText())
                .isEqualTo("Boutique du Nord");
        assertThat(mapper.readTree(corps).get(0).get("activeSalesReps").asInt()).isEqualTo(1);
        assertThat(corps).doesNotContain(SECRET_EN_CLAIR).doesNotContain(CLE_ERP_EN_CLAIR);
    }

    @Test
    void laFicheNeContientJamaisLeSecret() throws Exception {
        Client boutique = creeUneBoutique("Boutique du Sud");

        String corps = mockMvc.perform(get("/api/admin/clients/" + boutique.getId())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(corps).contains("Boutique du Sud").contains(boutique.getPublicKey());
        assertThat(corps).doesNotContain(SECRET_EN_CLAIR);
        // Les reglages non secrets sont rendus pour que le formulaire les reaffiche ;
        // la cle d'API, elle, ne revient jamais.
        assertThat(corps).contains("http://erp.test");
        assertThat(corps).doesNotContain(CLE_ERP_EN_CLAIR);
    }

    @Test
    void uneBoutiqueInconnueRend404() throws Exception {
        mockMvc.perform(get("/api/admin/clients/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sansJetonLaListeEstRefusee() throws Exception {
        mockMvc.perform(get("/api/admin/clients")).andExpect(status().isUnauthorized());
    }

    private Client creeUneBoutique(String nom) {
        Client client = new Client();
        client.setName(nom);
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret(SECRET_EN_CLAIR);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", CLE_ERP_EN_CLAIR));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
    }

    private void creeUnCommercial(Client boutique, String email, boolean actif) {
        SalesRep rep = new SalesRep();
        rep.setClient(boutique);
        rep.setFullName("Commercial " + email);
        rep.setEmail(email);
        rep.setActive(actif);
        commerciaux.save(rep);
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

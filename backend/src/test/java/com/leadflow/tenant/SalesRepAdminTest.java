package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
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
 * La regle qui compte est le refus de desactiver le dernier commercial actif : sans lui, le
 * routage leve AssignmentException et les leads de la boutique partent en DLQ. Elle est
 * verifiee ici, sur l'API, parce que c'est la qu'elle doit tenir — pas seulement a l'ecran.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class SalesRepAdminTest {

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
    void ajouteUnCommercial() throws Exception {
        Client boutique = creeUneBoutique("Boutique qui recrute");

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/sales-reps")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Karim Haddad\",\"email\":\"karim@test.fr\"}"))
                .andExpect(status().isCreated());

        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    @Test
    void refuseUnEmailDejaPrisDansLaMemeBoutique() throws Exception {
        Client boutique = creeUneBoutique("Boutique doublon");
        creeUnCommercial(boutique, "karim@test.fr", true);

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/sales-reps")
                        .header("Authorization", "Bearer " + jeton())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Karim Bis\",\"email\":\"karim@test.fr\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void refuseDeDesactiverLeDernierCommercialActif() throws Exception {
        Client boutique = creeUneBoutique("Boutique fragile");
        SalesRep seul = creeUnCommercial(boutique, "seul@test.fr", true);

        // Sans lui, AssignmentException a chaque lead, donc DLQ : l'ecran ne doit pas
        // laisser fabriquer cet etat.
        mockMvc.perform(post("/api/admin/sales-reps/" + seul.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isConflict());

        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    @Test
    void autoriseADesactiverQuandUnAutreResteActif() throws Exception {
        Client boutique = creeUneBoutique("Boutique fournie");
        SalesRep premier = creeUnCommercial(boutique, "un@test.fr", true);
        creeUnCommercial(boutique, "deux@test.fr", true);

        mockMvc.perform(post("/api/admin/sales-reps/" + premier.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    @Test
    void autoriseADesactiverLeDernierSiLaBoutiqueEstDejaDesactivee() throws Exception {
        Client boutique = creeUneBoutique("Boutique fermee");
        boutique.setActive(false);
        clients.save(boutique);
        SalesRep seul = creeUnCommercial(boutique, "seul@test.fr", true);

        // La regle protege le pipeline d'une boutique active. Fermee, elle ne capture plus.
        mockMvc.perform(post("/api/admin/sales-reps/" + seul.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());
    }

    private Client creeUneBoutique(String nom) {
        Client client = new Client();
        client.setName(nom);
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret("secret-hmac-de-test");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
    }

    private SalesRep creeUnCommercial(Client boutique, String email, boolean actif) {
        SalesRep rep = new SalesRep();
        rep.setClient(boutique);
        rep.setFullName("Commercial " + email);
        rep.setEmail(email);
        rep.setActive(actif);
        return commerciaux.save(rep);
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

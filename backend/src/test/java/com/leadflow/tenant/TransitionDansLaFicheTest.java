package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * La fiche doit repondre a « puis-je revoquer maintenant ? », et a rien d'autre : aucun
 * secret n'en sort, ni le courant ni le precedent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class TransitionDansLaFicheTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;

    @AfterEach
    void nettoie() {
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void horsTransitionLeChampEstNul() throws Exception {
        Client boutique = creeUneBoutique();

        assertThat(fiche(boutique.getId()).get("transition").isNull()).isTrue();
    }

    @Test
    void pendantLaTransitionLaFicheDonneLaDateDeFinEtAucunUsage() throws Exception {
        Client boutique = creeUneBoutique();
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                .header("Authorization", "Bearer " + jeton()));

        JsonNode transition = fiche(boutique.getId()).get("transition");

        assertThat(transition.get("expireLe").asText()).isNotBlank();
        // Aucun lead n'est arrive depuis la rotation : c'est le feu vert pour revoquer.
        assertThat(transition.get("dernierLeadAncienSecret").isNull()).isTrue();
    }

    @Test
    void laFicheDatteLeDernierLeadSigneAvecLAncienSecret() throws Exception {
        Client boutique = creeUneBoutique();
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                .header("Authorization", "Bearer " + jeton()));
        Instant quand = Instant.now().minus(2, ChronoUnit.HOURS);
        ecritUnLead(boutique.getId(), quand, true);
        // Un lead plus recent signe avec le secret courant ne doit pas deplacer la date :
        // ce qu'on cherche, c'est le dernier retardataire.
        ecritUnLead(boutique.getId(), Instant.now(), false);

        JsonNode transition = fiche(boutique.getId()).get("transition");

        assertThat(Instant.parse(transition.get("dernierLeadAncienSecret").asText()))
                .isCloseTo(quand, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void parmiPlusieursRetardatairesLaFicheGardeLePlusRecent() throws Exception {
        Client boutique = creeUneBoutique();
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                .header("Authorization", "Bearer " + jeton()));
        Instant lePlusAncien = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant lePlusRecent = Instant.now().minus(1, ChronoUnit.HOURS);
        // Les deux sont signes avec l'ancien secret : seul le tri distingue lequel remonter.
        ecritUnLead(boutique.getId(), lePlusAncien, true);
        ecritUnLead(boutique.getId(), lePlusRecent, true);

        JsonNode transition = fiche(boutique.getId()).get("transition");

        assertThat(Instant.parse(transition.get("dernierLeadAncienSecret").asText()))
                .isCloseTo(lePlusRecent, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void aucunSecretNeSortDeLaFiche() throws Exception {
        Client boutique = creeUneBoutique();
        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        String nouveau = mapper.readTree(corps).get("hmacSecret").asText();

        String fiche = mockMvc.perform(get("/api/admin/clients/" + boutique.getId())
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        assertThat(fiche).doesNotContain(nouveau).doesNotContain("secret-d-origine");
    }

    private JsonNode fiche(UUID id) throws Exception {
        String corps = mockMvc.perform(get("/api/admin/clients/" + id)
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps);
    }

    private void ecritUnLead(UUID clientId, Instant recu, boolean ancienSecret) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "prospect@test.fr"));
        evenement.setSignature("t=1,v1=" + UUID.randomUUID());
        evenement.setReceivedAt(recu);
        evenement.setStatus(RawLeadEventStatus.RECEIVED);
        evenement.setSignedWithPreviousSecret(ancienSecret);
        evenements.save(evenement);
    }

    private Client creeUneBoutique() {
        Client client = new Client();
        client.setName("Boutique observee");
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret("secret-d-origine");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

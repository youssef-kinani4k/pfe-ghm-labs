package com.leadflow.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptNature;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Eprouve ce que le test de service ne voit pas : la traduction des parametres de requete,
 * le plafond de taille de page, et le fait que l'endpoint soit bien derriere le jeton.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class LeadQueryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;

    private UUID clientId;

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Sud");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        clientId = clientRepository.saveAndFlush(client).getId();

        for (int i = 0; i < 3; i++) {
            RawLeadEvent evenement = new RawLeadEvent();
            evenement.setClientId(clientId);
            evenement.setSource("formulaire");
            evenement.setPayload(new HashMap<>(Map.of("email", "lead" + i + "@exemple.fr")));
            evenement.setSignature("sig-" + UUID.randomUUID());
            evenement.setReceivedAt(Instant.now());
            rawLeadEventRepository.saveAndFlush(evenement);

            Lead lead = new Lead();
            lead.setClientId(clientId);
            lead.setRawEventId(evenement.getId());
            lead.setEmail("lead" + i + "@exemple.fr");
            lead.setScore(i * 10);
            lead.setStatus(i == 0 ? LeadStatus.SYNCED : LeadStatus.QUALIFIED);
            leadRepository.saveAndFlush(lead);
        }
    }

    @AfterEach
    void nettoyage() {
        attemptRepository.deleteAll();
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void listeLesLeadsAvecUnJeton() throws Exception {
        mockMvc.perform(get("/api/leads").param("clientId", clientId.toString())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void leFiltreDeStatutEstRepetable() throws Exception {
        mockMvc.perform(get("/api/leads")
                        .param("status", "SYNCED")
                        .param("status", "REJECTED")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void laTailleDePageEstPlafonneeEnSilence() throws Exception {
        // Plafonne, pas refuse : une taille excessive est une maladresse d'appelant, pas
        // une faute qui merite de faire echouer l'ecran.
        mockMvc.perform(get("/api/leads").param("size", "5000")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void leDetailDUnLeadInconnuRend404EnProblemDetail() throws Exception {
        mockMvc.perform(get("/api/leads/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void leDetailRendLaChargeUtileBrute() throws Exception {
        String id = mockMvc.perform(get("/api/leads").param("clientId", clientId.toString())
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String leadId = mapper.readTree(id).get("content").get(0).get("id").asText();

        CrmSyncAttempt reaffectation = new CrmSyncAttempt();
        reaffectation.setLeadId(UUID.fromString(leadId));
        reaffectation.setProviderId("dolibarr");
        reaffectation.setNature(CrmSyncAttemptNature.REAFFECTATION);
        reaffectation.setStatus(CrmSyncAttemptStatus.SUCCESS);
        reaffectation.setAssigneeRef("9");
        reaffectation.setAttemptedAt(Instant.now());
        attemptRepository.saveAndFlush(reaffectation);

        // C'est le corps JSON, et non le DTO, qui prouve que la reaffectation et sa
        // reference de responsable traversent la frontiere HTTP — et que taskRef n'y figure
        // plus.
        mockMvc.perform(get("/api/leads/" + leadId)
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawEvent.payload.email").exists())
                .andExpect(jsonPath("$.syncAttempts").isArray())
                .andExpect(jsonPath("$.syncAttempts[0].nature").value("REAFFECTATION"))
                .andExpect(jsonPath("$.syncAttempts[0].assigneeRef").value("9"))
                .andExpect(jsonPath("$.syncAttempts[0].taskRef").doesNotExist());
    }

    @Test
    void sansJetonLEndpointEstRefuse() throws Exception {
        mockMvc.perform(get("/api/leads"))
                .andExpect(status().isUnauthorized());
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

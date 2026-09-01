package com.leadflow.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserte le <b>corps JSON</b> et non le DTO : c'est le corps qui est le contrat, et un
 * test sur le record ne prouverait rien de ce que voit le frontend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadTimelineControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    private UUID leadId;

    /**
     * Voir {@link LeadTimelineServiceTest} : ce que le test cree doit disparaitre, sans quoi
     * {@code capture/} ne peut plus vider {@code raw_lead_event}.
     */
    @AfterEach
    void nettoyage() {
        leads.deleteAll();
        evenements.deleteAll();
        clients.deleteAll();
    }

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Ouest");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        UUID clientId = clients.saveAndFlush(client).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(new HashMap<>(Map.of("email", "c@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.parse("2026-08-31T10:00:00Z"));
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("c@exemple.fr");
        lead.setScore(80);
        lead.setDetectedIntent("DEVIS");
        lead.setStatus(LeadStatus.QUALIFIED);
        leadId = leads.saveAndFlush(lead).getId();
    }

    @Test
    @WithMockUser
    void rendLaChronologieEnJson() throws Exception {
        mockMvc.perform(get("/api/leads/{id}/timeline", leadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CAPTURE"))
                .andExpect(jsonPath("$[0].outcome").value("NEUTRE"))
                .andExpect(jsonPath("$[0].details.source").value("formulaire-devis"))
                .andExpect(jsonPath("$[1].type").value("QUALIFICATION"))
                .andExpect(jsonPath("$[1].details.score").value("80"))
                .andExpect(jsonPath("$[1].details.intention").value("DEVIS"));
    }

    @Test
    @WithMockUser
    void leadInconnuRend404() throws Exception {
        mockMvc.perform(get("/api/leads/{id}/timeline", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sansJetonLEndpointRefuse() throws Exception {
        mockMvc.perform(get("/api/leads/{id}/timeline", leadId))
                .andExpect(status().isUnauthorized());
    }
}

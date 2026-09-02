package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserte le corps JSON, qui est le contrat, et non le record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadReassignmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LeadActionRepository actions;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    private UUID leadId;
    private UUID premier;
    private UUID second;

    @AfterEach
    void nettoyage() {
        actions.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Controleur");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        Client boutique = clients.saveAndFlush(client);

        SalesRep un = new SalesRep();
        un.setClient(boutique);
        un.setFullName("Amina Bensalem");
        un.setEmail("amina+" + UUID.randomUUID() + "@demo.test");
        un.setActive(true);
        premier = commerciaux.saveAndFlush(un).getId();

        SalesRep deux = new SalesRep();
        deux.setClient(boutique);
        deux.setFullName("Karim Haddad");
        deux.setEmail("karim+" + UUID.randomUUID() + "@demo.test");
        deux.setActive(true);
        second = commerciaux.saveAndFlush(deux).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique.getId());
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "d@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(boutique.getId());
        lead.setRawEventId(rawEventId);
        lead.setEmail("d@exemple.fr");
        lead.setScore(70);
        lead.setStatus(LeadStatus.ROUTED);
        lead.setAssignedSalesRepId(premier);
        lead.setRoutedAt(Instant.now());
        leadId = leads.saveAndFlush(lead).getId();
    }

    @Test
    @WithMockUser(username = "operateur-du-jeton")
    void rendLaFicheRechargeeEtJournaliseLOperateurDuJeton() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", leadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + second + "\",\"reason\":\"Conge\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesRep.id").value(second.toString()))
                .andExpect(jsonPath("$.salesRep.fullName").value("Karim Haddad"));

        List<LeadAction> journal = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(journal).hasSize(1);
        // Le corps ne portait aucun acteur : il ne peut venir que du jeton.
        assertThat(journal.get(0).getActor()).isEqualTo("operateur-du-jeton");
    }

    @Test
    @WithMockUser
    void unLeadInconnuRend404() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + second + "\",\"reason\":\"Conge\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void leCommercialDejaEnPlaceRend409() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", leadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + premier + "\",\"reason\":\"Conge\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    void unMotifVideRend400() throws Exception {
        mockMvc.perform(post("/api/leads/{id}/reassign", leadId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesRepId\":\"" + second + "\",\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(actions.findByLeadIdOrderByCreatedAtAsc(leadId)).isEmpty();
    }
}

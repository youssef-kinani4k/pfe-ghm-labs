package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadActionRepositoryTest {

    @Autowired private LeadActionRepository actions;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    /**
     * La base Testcontainers est partagee par toute la suite : un lead survivant empeche
     * {@code capture/} de vider {@code raw_lead_event}, et vingt-cinq tests tombent.
     */
    @AfterEach
    void nettoyage() {
        actions.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void uneActionFaitLAllerRetourSansRienPerdre() {
        UUID leadId = unLead();
        UUID ancien = UUID.randomUUID();
        UUID nouveau = UUID.randomUUID();

        LeadAction action = new LeadAction();
        action.setLeadId(leadId);
        action.setAction(LeadActionType.REATTRIBUTION);
        action.setActor("admin");
        action.setReason("Depart en conge");
        action.setPreviousSalesRepId(ancien);
        action.setNewSalesRepId(nouveau);
        action.setOutcome(LeadActionOutcome.SUCCES);
        actions.saveAndFlush(action);

        List<LeadAction> relues = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(relues).hasSize(1);
        LeadAction relue = relues.get(0);
        assertThat(relue.getAction()).isEqualTo(LeadActionType.REATTRIBUTION);
        assertThat(relue.getActor()).isEqualTo("admin");
        assertThat(relue.getReason()).isEqualTo("Depart en conge");
        assertThat(relue.getPreviousSalesRepId()).isEqualTo(ancien);
        assertThat(relue.getNewSalesRepId()).isEqualTo(nouveau);
        assertThat(relue.getDeadLetterId()).isNull();
        assertThat(relue.getOutcome()).isEqualTo(LeadActionOutcome.SUCCES);
        assertThat(relue.getCreatedAt()).isNotNull();
    }

    private UUID unLead() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Journal");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        UUID clientId = clients.saveAndFlush(client).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "a@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("a@exemple.fr");
        lead.setScore(40);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leads.saveAndFlush(lead).getId();
    }
}

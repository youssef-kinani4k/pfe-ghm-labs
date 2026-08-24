package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.ConnectorView;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
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
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConnectorHealthServiceTest {

    @Autowired private ConnectorHealthService service;
    @Autowired private CrmSyncAttemptRepository attempts;
    @Autowired private LeadRepository leads;
    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;

    /**
     * Les agregats sont globaux — aucun filtre par client — et les etapes amont laissent
     * leurs propres traces derriere elles quand la suite entiere tourne. Le nettoyage est
     * donc en amont aussi, sans quoi ce test ne passerait que seul.
     */
    @BeforeEach
    void videLesTraces() {
        attempts.deleteAll();
    }

    @AfterEach
    void nettoie() {
        attempts.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        clients.deleteAll();
    }

    @Test
    void listeLesFournisseursDeclaresMemeSansAucuneTrace() {
        List<ConnectorView> vues = service.etatDesConnecteurs();

        assertThat(vues).extracting(ConnectorView::providerId).contains("dolibarr", "odoo");
        assertThat(vues).allSatisfy(vue -> assertThat(vue.enabled()).isTrue());
        assertThat(vues).allSatisfy(vue -> assertThat(vue.successCount()).isZero());
    }

    @Test
    void agregeSuccesEtEchecsParFournisseur() {
        UUID clientId = creeUnClient();
        UUID leadId = creeUnLead(clientId);
        trace(leadId, "dolibarr", CrmSyncAttemptStatus.SUCCESS, null);
        trace(leadId, "dolibarr", CrmSyncAttemptStatus.FAILED, "401 Unauthorized");

        ConnectorView dolibarr = trouve("dolibarr");

        assertThat(dolibarr.successCount()).isEqualTo(1L);
        assertThat(dolibarr.failureCount()).isEqualTo(1L);
        assertThat(dolibarr.lastFailureMessage()).isEqualTo("401 Unauthorized");
        assertThat(dolibarr.lastSuccessAt()).isNotNull();
    }

    @Test
    void detailleLActiviteParClient() {
        UUID premier = creeUnClient();
        UUID second = creeUnClient();
        trace(creeUnLead(premier), "dolibarr", CrmSyncAttemptStatus.SUCCESS, null);
        trace(creeUnLead(second), "dolibarr", CrmSyncAttemptStatus.FAILED, "timeout");

        assertThat(trouve("dolibarr").parClient()).hasSize(2);
    }

    private ConnectorView trouve(String providerId) {
        return service.etatDesConnecteurs().stream()
                .filter(vue -> vue.providerId().equals(providerId))
                .findFirst()
                .orElseThrow();
    }

    private UUID creeUnClient() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client " + UUID.randomUUID());
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        return clients.saveAndFlush(client).getId();
    }

    private UUID creeUnLead(UUID clientId) {
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(creeUnEvenement(clientId));
        lead.setEmail(UUID.randomUUID() + "@test.fr");
        lead.setScore(10);
        return leads.saveAndFlush(lead).getId();
    }

    /** {@code lead.raw_event_id} porte une cle etrangere : le journal de capture d'abord. */
    private UUID creeUnEvenement(UUID clientId) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "a@b.fr")));
        evenement.setSignature("t=1,v1=" + UUID.randomUUID());
        evenement.setStatus(RawLeadEventStatus.PUBLISHED);
        evenement.setReceivedAt(Instant.now());
        return evenements.saveAndFlush(evenement).getId();
    }

    private void trace(UUID leadId, String providerId, CrmSyncAttemptStatus statut, String erreur) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(statut);
        tentative.setErrorMessage(erreur);
        attempts.saveAndFlush(tentative);
    }
}

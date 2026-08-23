package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.LeadDetail;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadDetailServiceTest {

    @Autowired private LeadDetailService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @AfterEach
    void nettoyage() {
        attemptRepository.deleteAll();
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void rendLeLeadSonCommercialSesTentativesEtSonEvenementBrut() {
        Client client = creeUnClient();
        UUID clientId = client.getId();

        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Karim Daoud");
        commercial.setEmail("karim@exemple.fr");
        commercial.setSector("industrie");
        UUID commercialId = salesRepRepository.saveAndFlush(commercial).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-contact");
        evenement.setPayload(
                new HashMap<>(Map.of("email", "prospect@exemple.fr", "tel", "0102030405")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        rawLeadEventRepository.saveAndFlush(evenement);

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(evenement.getId());
        lead.setEmail("prospect@exemple.fr");
        lead.setPhone("0102030405");
        lead.setMessage("Besoin d'un devis");
        lead.setScore(70);
        lead.setStatus(LeadStatus.SYNCED);
        lead.setAssignedSalesRepId(commercialId);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();

        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("odoo");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAccountRef("42");
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.saveAndFlush(tentative);

        LeadDetail detail = service.detail(leadId);

        assertThat(detail.email()).isEqualTo("prospect@exemple.fr");
        assertThat(detail.message()).isEqualTo("Besoin d'un devis");
        assertThat(detail.clientName()).isEqualTo("Agence Sud");
        assertThat(detail.salesRep().fullName()).isEqualTo("Karim Daoud");
        assertThat(detail.syncAttempts()).hasSize(1);
        assertThat(detail.syncAttempts().getFirst().accountRef()).isEqualTo("42");
        assertThat(detail.rawEvent().source()).isEqualTo("formulaire-contact");
        // La charge utile brute est incluse deliberement : c'est l'ecran ou l'on repond a
        // « pourquoi ce lead n'a pas de telephone ».
        assertThat(detail.rawEvent().payload()).containsEntry("tel", "0102030405");
    }

    @Test
    void ordonneLesTentativesDeLaPlusRecenteALaPlusAncienne() {
        Client client = creeUnClient();
        UUID leadId = creeUnLeadSimple(client.getId());

        trace(leadId, CrmSyncAttemptStatus.FAILED, "premier",
                Instant.now().minusSeconds(600));
        trace(leadId, CrmSyncAttemptStatus.SUCCESS, "second", Instant.now());

        LeadDetail detail = service.detail(leadId);

        assertThat(detail.syncAttempts()).extracting(vue -> vue.accountRef())
                .containsExactly("second", "premier");
    }

    @Test
    void unLeadSansCommercialNiTentativeResteLisible() {
        Client client = creeUnClient();
        UUID leadId = creeUnLeadSimple(client.getId());

        LeadDetail detail = service.detail(leadId);

        // Etat d'un lead qui vient d'etre qualifie : ni attribue, ni synchronise. L'ecran
        // doit l'afficher, pas tomber.
        assertThat(detail.salesRep()).isNull();
        assertThat(detail.syncAttempts()).isEmpty();
        assertThat(detail.rawEvent()).isNotNull();
    }

    @Test
    void identifiantInconnuLeveUneRessourceIntrouvable() {
        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOf(RessourceIntrouvableException.class);
    }

    private Client creeUnClient() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Sud");
        client.setHmacSecret("secret");
        client.setCrmProviderId("odoo");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost"));
        return clientRepository.saveAndFlush(client);
    }

    private UUID creeUnLeadSimple(UUID clientId) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "simple@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        evenement.setStatus(RawLeadEventStatus.PUBLISHED);
        rawLeadEventRepository.saveAndFlush(evenement);

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(evenement.getId());
        lead.setEmail("simple@exemple.fr");
        lead.setScore(20);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leadRepository.saveAndFlush(lead).getId();
    }

    private void trace(UUID leadId, CrmSyncAttemptStatus statut, String ref, Instant quand) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("odoo");
        tentative.setStatus(statut);
        tentative.setAccountRef(ref);
        tentative.setAttemptedAt(quand);
        attemptRepository.saveAndFlush(tentative);
    }
}

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

/**
 * L'attribution est un seul fait : le statut, le commercial et la date sont ecrits
 * ensemble ou pas du tout.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoutedLeadWriterTest {

    @Autowired private RoutedLeadWriter writer;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;

    /**
     * Voir la meme methode dans {@code LeadTimelineServiceTest} : la base Testcontainers est
     * partagee par toute la suite, et un lead survivant bloque le vidage de
     * {@code raw_lead_event} par {@code capture/}.
     */
    @AfterEach
    void nettoyage() {
        leads.deleteAll();
        evenements.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @Test
    void poseLaDateDAttributionEnMemeTempsQueLeStatut() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Nord");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        Client boutique = clients.saveAndFlush(client);
        UUID clientId = boutique.getId();

        // SalesRep porte une association vers Client, pas un clientId brut — contrairement
        // a Lead, qui reste en identifiants pour ne pas dependre du package tenant.
        SalesRep commercial = new SalesRep();
        commercial.setClient(boutique);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina+" + UUID.randomUUID() + "@demo.test");
        commercial.setActive(true);
        UUID salesRepId = commerciaux.saveAndFlush(commercial).getId();

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
        UUID leadId = leads.saveAndFlush(lead).getId();

        Instant avant = Instant.now();
        writer.attribue(leadId, salesRepId);

        Lead relu = leads.findById(leadId).orElseThrow();
        assertThat(relu.getStatus()).isEqualTo(LeadStatus.ROUTED);
        assertThat(relu.getAssignedSalesRepId()).isEqualTo(salesRepId);
        assertThat(relu.getRoutedAt()).isNotNull();
        assertThat(relu.getRoutedAt()).isAfterOrEqualTo(avant);
    }

    @Test
    void unLeadNonAttribueNaPasDeDate() {
        // Le lead cree par le test precedent n'est pas relu ici : on eprouve le defaut
        // d'une ligne neuve, qui est ce que verra tout l'historique anterieur a V7.
        Lead lead = new Lead();
        assertThat(lead.getRoutedAt()).isNull();
    }
}

package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadRoutingServiceTest {

    @Autowired private LeadRoutingService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private RabbitTemplate rabbitTemplate;

    private Client client;

    @BeforeEach
    void preparation() {
        while (rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE) != null) {
            // vide la file avant chaque test : sans quoi un test lit la publication du precedent.
        }
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client = clientRepository.saveAndFlush(client);
    }

    /** La base est partagee : ne rien laisser derriere soi. */
    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
    }

    private SalesRep commercial(String email, boolean actif) {
        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Commercial " + email);
        commercial.setEmail(email);
        commercial.setActive(actif);
        return salesRepRepository.saveAndFlush(commercial);
    }

    private UUID leadQualifie() {
        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(client.getId());
        brut.setSource("formulaire-devis");
        brut.setPayload(Map.of("email", "prospect@acme.test"));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.saveAndFlush(brut).getId();

        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setRawEventId(eventId);
        lead.setEmail("prospect+" + UUID.randomUUID() + "@acme.test");
        lead.setScore(60);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leadRepository.saveAndFlush(lead).getId();
    }

    @Test
    void attribueLeLeadEtLeMarqueRoute() {
        SalesRep amina = commercial("amina@demo.test", true);

        Lead route = service.route(leadQualifie()).orElseThrow();

        assertThat(route.getAssignedSalesRepId()).isEqualTo(amina.getId());
        assertThat(route.getStatus()).isEqualTo(LeadStatus.ROUTED);
    }

    @Test
    void repartitTroisLeadsSurDeuxCommerciauxSansEnOublierUn() {
        commercial("amina@demo.test", true);
        commercial("karim@demo.test", true);

        UUID premier = service.route(leadQualifie()).orElseThrow().getAssignedSalesRepId();
        UUID second = service.route(leadQualifie()).orElseThrow().getAssignedSalesRepId();
        UUID troisieme = service.route(leadQualifie()).orElseThrow().getAssignedSalesRepId();

        assertThat(premier).isNotEqualTo(second);
        assertThat(troisieme).isEqualTo(premier);
    }

    @Test
    void neRetientJamaisUnCommercialInactif() {
        commercial("inactif@demo.test", false);
        SalesRep actif = commercial("actif@demo.test", true);

        Lead route = service.route(leadQualifie()).orElseThrow();

        assertThat(route.getAssignedSalesRepId()).isEqualTo(actif.getId());
    }

    /** Reparable par un humain — activer un commercial — donc le rejeu depuis la DLQ a un sens. */
    @Test
    void leveQuandAucunCommercialNEstActif() {
        commercial("inactif@demo.test", false);
        UUID leadId = leadQualifie();

        assertThatThrownBy(() -> service.route(leadId))
                .isInstanceOf(AssignmentException.class)
                .hasMessageContaining(client.getId().toString());
    }

    /** La livraison est at-least-once : un rejeu ne doit pas decaler la rotation. */
    @Test
    void neReattribuePasUnLeadDejaRoute() {
        commercial("amina@demo.test", true);
        commercial("karim@demo.test", true);
        UUID leadId = leadQualifie();
        UUID premier = service.route(leadId).orElseThrow().getAssignedSalesRepId();

        UUID second = service.route(leadId).orElseThrow().getAssignedSalesRepId();

        assertThat(second).isEqualTo(premier);
    }

    @Test
    void acquitteUnLeadIntrouvableSansLever() {
        assertThat(service.route(UUID.randomUUID())).isEmpty();
    }

    @Test
    void publieLeLeadRouteSurLaFileDeSortie() {
        SalesRep amina = commercial("amina@demo.test", true);

        Lead route = service.route(leadQualifie()).orElseThrow();

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.ROUTED_QUEUE, 5000);
        assertThat(recu).isInstanceOf(RoutedLeadMessage.class);
        RoutedLeadMessage message = (RoutedLeadMessage) recu;
        assertThat(message.leadId()).isEqualTo(route.getId());
        assertThat(message.clientId()).isEqualTo(client.getId());
        assertThat(message.salesRepId()).isEqualTo(amina.getId());
        assertThat(message.routedAt()).isNotNull();
    }

    /**
     * Republier un lead deja attribue est sans danger — CrmSyncService rejoue via
     * CrmSyncState — alors que ne pas republier laisserait un lead ROUTED que plus rien ne
     * synchroniserait.
     */
    @Test
    void republieUnLeadDejaRouteSansLeReattribuer() {
        commercial("amina@demo.test", true);
        UUID leadId = leadQualifie();
        service.route(leadId);
        rabbitTemplate.receiveAndConvert(RabbitMQConfig.ROUTED_QUEUE, 5000);

        service.route(leadId);

        assertThat(rabbitTemplate.receiveAndConvert(RabbitMQConfig.ROUTED_QUEUE, 5000))
                .isInstanceOf(RoutedLeadMessage.class);
    }
}

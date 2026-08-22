package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.qualification.QualifiedLeadMessage;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * La suite retire le consommateur du routage — sans quoi il volerait aux tests de la
 * qualification les messages qu'ils viennent de publier — donc cette classe le rallume par
 * propriete, dans son propre contexte. Le consommateur de synchronisation reste eteint : ce
 * qui est verifie ici est le cablage du routage, pas la chaine complete.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.routing.listener.enabled=true",
        "leadflow.crm.listener.enabled=false"})
class LeadRoutingListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @BeforeEach
    void purgeDeLaFileDeSortie() {
        while (rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE) != null) {
            // vide la file avant le test : un message resteant d'un test precedent designe
            // un lead supprime depuis, et ferait echouer la synchronisation en boucle.
        }
    }

    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
    }

    @Test
    void routeUnLeadRecuParLaFile() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        Client enregistre = clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(enregistre);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina@demo.test");
        SalesRep amina = salesRepRepository.saveAndFlush(commercial);

        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(enregistre.getId());
        brut.setSource("formulaire-devis");
        brut.setPayload(Map.of("email", "prospect@acme.test"));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.saveAndFlush(brut).getId();

        Lead lead = new Lead();
        lead.setClientId(enregistre.getId());
        lead.setRawEventId(eventId);
        lead.setEmail("prospect@acme.test");
        lead.setScore(80);
        lead.setStatus(LeadStatus.QUALIFIED);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.QUALIFIED_ROUTING_KEY,
                new QualifiedLeadMessage(leadId, enregistre.getId(), 80, Instant.now()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Lead route = leadRepository.findById(leadId).orElseThrow();
            assertThat(route.getStatus()).isEqualTo(LeadStatus.ROUTED);
            assertThat(route.getAssignedSalesRepId()).isEqualTo(amina.getId());
        });
    }
}

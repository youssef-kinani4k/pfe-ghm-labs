package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Le pipeline complet, listeners rallumes : un message de qualification entre, un lead
 * SYNCED et sa trace sortent. Le connecteur est un espion — aucun test de F4 ne doit
 * dependre d'un vrai Dolibarr.
 */
@SpringBootTest(properties = "leadflow.crm.providers.espion.enabled=true")
@Import({TestcontainersConfiguration.class, CrmSyncListenerTest.ConnecteurDeTest.class})
@TestPropertySource(properties = {
        "leadflow.routing.listener.enabled=true",
        "leadflow.crm.listener.enabled=true"})
class CrmSyncListenerTest {

    /** Connecteur qui n'existe que pour ce test : il valide la chaine, pas un protocole. */
    @TestConfiguration
    static class ConnecteurDeTest {

        @Bean
        CrmConnector connecteurEspion() {
            return new CrmConnector() {

                @Override
                public String providerId() {
                    return "espion";
                }

                @Override
                public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
                    return new CrmSyncResult("espion", "A-1", "C-1", "O-1", null, Instant.now());
                }

                @Override
                public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
                    return "U-1";
                }

                @Override
                public List<CrmSettingSpec> reglagesAttendus() {
                    return List.of();
                }

                @Override
                public CrmCheck verifieAcces(CrmTarget cible) {
                    return CrmCheck.joignable(null);
                }
            };
        }
    }

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;

    @BeforeEach
    void purgeDeLaFileDeSortie() {
        while (rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE) != null) {
            // vide la file avant le test : un message resteant d'un test precedent designe
            // un lead supprime depuis, et ferait echouer la synchronisation en boucle.
        }
    }

    @AfterEach
    void nettoyage() {
        attemptRepository.deleteAll();
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
    }

    @Test
    void routePuisSynchroniseUnLeadVenantDeLaFile() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("espion");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        Client enregistre = clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(enregistre);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina@demo.test");
        salesRepRepository.saveAndFlush(commercial);

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

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Lead synchronise = leadRepository.findById(leadId).orElseThrow();
            assertThat(synchronise.getStatus()).isEqualTo(LeadStatus.SYNCED);
            assertThat(synchronise.getAssignedSalesRepId()).isNotNull();
            assertThat(attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(
                    leadId, "espion")).isNotEmpty();
        });
    }
}

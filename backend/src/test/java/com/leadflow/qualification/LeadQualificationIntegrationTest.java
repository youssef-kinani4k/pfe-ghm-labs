package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Gemini est desactive par propriete : aucun appel reseau sortant en CI. C'est aussi ce qui
 * rend le mode degrade verifiable — {@code intent_source} doit valoir RULES partout ici.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "leadflow.intent.gemini.enabled=false")
class LeadQualificationIntegrationTest {

    @Autowired private LeadQualificationService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RabbitTemplate rabbitTemplate;

    private UUID clientId;

    @BeforeEach
    void preparation() {
        while (rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE) != null) {
            // vide la file avant chaque test : sans quoi un test herite des messages du
            // precedent et lit une publication qui n'est pas la sienne.
        }
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        clientId = clientRepository.save(client).getId();
    }

    /**
     * La base est partagee par toute la suite : laisser des leads derriere soi ferait
     * echouer la classe suivante qui vide {@code raw_lead_event} sans passer par les leads.
     */
    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
    }

    private UUID evenement(Map<String, Object> payload) {
        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(clientId);
        brut.setSource("formulaire-devis");
        brut.setPayload(new HashMap<>(payload));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        return rawLeadEventRepository.save(brut).getId();
    }

    @Test
    void qualifieUnLeadCompletEtLeMarqueQualified() {
        UUID id = evenement(Map.of(
                "email", "Karim@ACME.test",
                "telephone", "06 12 34 56 78",
                "societe", "ACME",
                "nom", "Bennani",
                "message", "Je veux un devis pour 50 unites"));

        Lead lead = service.qualifie(id).orElseThrow();

        assertThat(lead.getEmail()).isEqualTo("karim@acme.test");
        assertThat(lead.getPhone()).isEqualTo("0612345678");
        assertThat(lead.getCompanyName()).isEqualTo("ACME");
        assertThat(lead.getDetectedIntent()).isEqualTo(LeadIntent.DEVIS.name());
        assertThat(lead.getIntentSource()).isEqualTo(IntentSource.RULES);
        assertThat(lead.getScore()).isEqualTo(80);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.QUALIFIED);
        assertThat(lead.getAssignedSalesRepId()).isNull();
    }

    @Test
    void nEcritAucunLeadEtMarqueLEvenementEnEchecSansEmail() {
        UUID id = evenement(Map.of("message", "Bonjour"));

        assertThat(service.qualifie(id)).isEmpty();
        assertThat(leadRepository.count()).isZero();

        RawLeadEvent brut = rawLeadEventRepository.findById(id).orElseThrow();
        // DISCARDED et non FAILED : ce dernier est rebalaye par PendingEventRelay, qui
        // republierait sans fin un evenement que la qualification rejettera toujours.
        assertThat(brut.getStatus()).isEqualTo(RawLeadEventStatus.DISCARDED);
        assertThat(brut.getFailureReason()).contains("email");
    }

    @Test
    void qualifieUnLeadSansTexteLibre() {
        UUID id = evenement(Map.of("email", "karim@acme.test"));

        Lead lead = service.qualifie(id).orElseThrow();

        assertThat(lead.getStatus()).isEqualTo(LeadStatus.QUALIFIED);
        assertThat(lead.getDetectedIntent()).isEqualTo(LeadIntent.AUTRE.name());
        assertThat(lead.getScore()).isZero();
    }

    @Test
    void rejetteUnDoublonDansLaFenetre() {
        service.qualifie(evenement(Map.of("email", "karim@acme.test")));
        UUID second = evenement(Map.of("email", "Karim@ACME.test"));

        Lead doublon = service.qualifie(second).orElseThrow();

        assertThat(doublon.getStatus()).isEqualTo(LeadStatus.REJECTED);
        assertThat(doublon.getScore()).isZero();
        assertThat(leadRepository.count()).isEqualTo(2);
    }

    @Test
    void neQualifiePasDeuxFoisLeMemeEvenement() {
        UUID id = evenement(Map.of("email", "karim@acme.test"));

        Lead premier = service.qualifie(id).orElseThrow();
        Lead second = service.qualifie(id).orElseThrow();

        assertThat(second.getId()).isEqualTo(premier.getId());
        assertThat(leadRepository.count()).isEqualTo(1);
    }

    @Test
    void neCreeQuUnSeulLeadSurDeuxLivraisonsConcurrentes() throws Exception {
        UUID id = evenement(Map.of("email", "karim@acme.test"));
        CountDownLatch depart = new CountDownLatch(1);
        CountDownLatch arrivee = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    depart.await();
                    service.qualifie(id);
                } catch (Exception ignore) {
                    // Le test porte sur l'etat final, pas sur qui a gagne.
                } finally {
                    arrivee.countDown();
                }
            });
        }
        depart.countDown();
        assertThat(arrivee.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(leadRepository.count()).isEqualTo(1);
    }

    @Test
    void acquitteSansRienEcrireQuandLEvenementEstIntrouvable() {
        Optional<Lead> resultat = service.qualifie(UUID.randomUUID());

        assertThat(resultat).isEmpty();
        assertThat(leadRepository.count()).isZero();
    }

    @Test
    void qualifieNormalementUnLeadDontLeClientAEteDesactive() {
        UUID id = evenement(Map.of("email", "karim@acme.test"));
        Client client = clientRepository.findById(clientId).orElseThrow();
        client.setActive(false);
        clientRepository.save(client);

        assertThat(service.qualifie(id).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.QUALIFIED);
    }

    @Test
    void publieLeLeadQualifieSurLaFileDeSortie() {
        UUID id = evenement(Map.of(
                "email", "karim@acme.test", "message", "Je veux un devis"));

        Lead lead = service.qualifie(id).orElseThrow();

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUALIFIED_QUEUE, 5000);
        assertThat(recu).isInstanceOf(QualifiedLeadMessage.class);
        QualifiedLeadMessage message = (QualifiedLeadMessage) recu;
        assertThat(message.leadId()).isEqualTo(lead.getId());
        assertThat(message.clientId()).isEqualTo(clientId);
        assertThat(message.score()).isEqualTo(lead.getScore());
        assertThat(message.qualifiedAt()).isNotNull();
    }

    @Test
    void nePubliePasUnDoublonRejete() {
        service.qualifie(evenement(Map.of("email", "karim@acme.test")));
        rabbitTemplate.receiveAndConvert(RabbitMQConfig.QUALIFIED_QUEUE, 5000);

        service.qualifie(evenement(Map.of("email", "karim@acme.test")));

        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 2000)).isNull();
    }
}

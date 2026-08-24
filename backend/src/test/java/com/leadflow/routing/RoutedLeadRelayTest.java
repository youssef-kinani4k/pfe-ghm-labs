package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.deadletter.DeadLetter;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoutedLeadRelayTest {

    @Autowired private RoutedLeadRelay filet;
    @Autowired private LeadRepository leads;
    @Autowired private DeadLetterRepository morts;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcTemplate jdbc;

    // Les etapes amont laissent derriere elles des leads et des messages : le filet
    // balaye toute la table, donc l'ardoise doit etre propre avant comme apres, sans quoi
    // un reste d'une autre classe de test se ferait republier ici.
    @BeforeEach
    @AfterEach
    void nettoie() {
        morts.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        clients.deleteAll();
        while (rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE, 100) != null) {
            // on vide la file entre deux tests
        }
    }

    @Test
    void republieUnLeadRouteAssezVieux() {
        UUID leadId = creeUnLeadRouteVieuxDe(Duration.ofMinutes(10));

        filet.republieLesNonSynchronises();

        assertThat(rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE, 5000)).isNotNull();
        assertThat(leadId).isNotNull();
    }

    @Test
    void ignoreUnLeadTropRecent() {
        creeUnLeadRouteVieuxDe(Duration.ofSeconds(5));

        filet.republieLesNonSynchronises();

        // Un delai plus long qu'une publication normale : le filet ne double jamais un
        // envoi en cours.
        assertThat(rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE, 500)).isNull();
    }

    @Test
    void ignoreUnLeadDejaMortEtEnAttenteDActionHumaine() {
        UUID leadId = creeUnLeadRouteVieuxDe(Duration.ofMinutes(10));
        morts.saveAndFlush(mortEnAttente(leadId));

        filet.republieLesNonSynchronises();

        // Critere de recette 7 : sans ce garde-fou, un client sans commercial actif ferait
        // republier toutes les 30 secondes et grossir le journal a chaque tour.
        assertThat(rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE, 500)).isNull();
    }

    @Test
    void republieDeNouveauUneFoisLaMortEcartee() {
        UUID leadId = creeUnLeadRouteVieuxDe(Duration.ofMinutes(10));
        DeadLetter mort = morts.saveAndFlush(mortEnAttente(leadId));
        mort.setStatus(DeadLetterStatus.DISCARDED);
        morts.saveAndFlush(mort);

        filet.republieLesNonSynchronises();

        assertThat(rabbitTemplate.receive(RabbitMQConfig.ROUTED_QUEUE, 5000)).isNotNull();
    }

    private UUID creeUnLeadRouteVieuxDe(Duration age) {
        // client et raw_lead_event portent des cles etrangeres : un identifiant invente ne
        // passe pas, et chaque lead a de toute facon son client et son evenement d'origine.
        UUID clientId = creeUnClient();
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(creeUnEvenement(clientId));
        lead.setEmail(UUID.randomUUID() + "@test.fr");
        lead.setScore(42);
        lead.setStatus(LeadStatus.ROUTED);
        UUID id = leads.saveAndFlush(lead).getId();
        // created_at est pose par @PrePersist : le vieillir en SQL est le seul moyen de
        // tester le seuil sans attendre.
        jdbc.update("update lead set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), id);
        return id;
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

    private DeadLetter mortEnAttente(UUID leadId) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue(RabbitMQConfig.ROUTED_QUEUE);
        mort.setRoutingKey(RabbitMQConfig.ROUTED_ROUTING_KEY);
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(DeadLetterStatus.PENDING);
        return mort;
    }
}

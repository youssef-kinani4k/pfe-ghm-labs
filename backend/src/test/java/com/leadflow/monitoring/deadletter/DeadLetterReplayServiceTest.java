package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.qualification.QualifiedLeadMessage;
import com.leadflow.routing.LeadAction;
import com.leadflow.routing.LeadActionOutcome;
import com.leadflow.routing.LeadActionRepository;
import com.leadflow.routing.LeadActionType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterReplayServiceTest {

    @Autowired private DeadLetterReplayService service;
    @Autowired private DeadLetterRepository repository;
    @Autowired private DeadLetterJournal journal;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadActionRepository actions;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    @AfterEach
    void nettoie() {
        actions.deleteAll();
        repository.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        clients.deleteAll();
        // La file est partagee par la suite : la vider evite qu'un message rejoue ici soit
        // lu par un autre test.
        while (rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 100) != null) {
            // on vide
        }
    }

    @Test
    void republieLesOctetsDOrigineAvecLeMemeTypeId() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.rejoue(mort.getId(), "operateur", "Panne broker resolue");

        Message republie = rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000);
        assertThat(republie).isNotNull();
        assertThat(new String(republie.getBody())).isEqualTo(mort.getPayload());
        assertThat(republie.getMessageProperties().getHeaders())
                .containsEntry("__TypeId__", QualifiedLeadMessage.class.getName());
    }

    @Test
    void marqueLaLigneRejoueeAvecLeNomDeLOperateur() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.rejoue(mort.getId(), "camille", "Panne broker resolue");

        assertThat(repository.findById(mort.getId())).hasValueSatisfying(relue -> {
            assertThat(relue.getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
            assertThat(relue.getReplayedBy()).isEqualTo("camille");
            assertThat(relue.getReplayedAt()).isNotNull();
        });
    }

    @Test
    void unSecondRejeuEstRefuse() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());
        service.rejoue(mort.getId(), "camille", "Panne broker resolue");

        assertThatThrownBy(() -> service.rejoue(mort.getId(), "camille", "Panne broker resolue"))
                .isInstanceOf(DejaTraiteException.class);
    }

    @Test
    void ecarterMarqueSansRienRepublier() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.ecarte(mort.getId(), "camille", "Message obsolete");

        assertThat(repository.findById(mort.getId()).orElseThrow().getStatus())
                .isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 500)).isNull();
    }

    @Test
    void mortInconnueRendUneRessourceIntrouvable() {
        assertThatThrownBy(() -> service.rejoue(UUID.randomUUID(), "camille", "motif"))
                .isInstanceOf(com.leadflow.common.RessourceIntrouvableException.class);
    }

    @Test
    void unEcartEcritSaLigneDeJournal() {
        UUID leadId = unLeadEnBase();
        DeadLetter mort = journal.enregistre(morteFor(leadId));

        service.ecarte(mort.getId(), "admin", "Doublon d un formulaire de test");

        List<LeadAction> lignes = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).getAction()).isEqualTo(LeadActionType.ECART);
        assertThat(lignes.get(0).getActor()).isEqualTo("admin");
        assertThat(lignes.get(0).getReason()).isEqualTo("Doublon d un formulaire de test");
        assertThat(lignes.get(0).getDeadLetterId()).isEqualTo(mort.getId());
        assertThat(lignes.get(0).getOutcome()).isEqualTo(LeadActionOutcome.SUCCES);
    }

    @Test
    void uneMortSansLeadNEcritAucuneLigne() {
        // lead_action.lead_id porte une cle etrangere NOT NULL : une mort dont on n'a pas su
        // tirer d'identifiant de lead n'a nulle part ou etre journalisee, et c'est normal.
        DeadLetter mort = journal.enregistre(morteFor(null));

        service.ecarte(mort.getId(), "admin", "Message illisible");

        assertThat(actions.findAll()).isEmpty();
    }

    private UUID unLeadEnBase() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Rejeu");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        UUID clientId = clients.saveAndFlush(client).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "e@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("e@exemple.fr");
        lead.setScore(30);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leads.saveAndFlush(lead).getId();
    }

    private DeadLetter morteFor(UUID leadId) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(DeadLetterStatus.PENDING);
        mort.setDeadAt(Instant.now());
        return mort;
    }

    private DeadLetter mortEnAttente() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue(RabbitMQConfig.QUALIFIED_QUEUE);
        mort.setRoutingKey(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        mort.setPayload("{\"leadId\":\"" + UUID.randomUUID() + "\",\"clientId\":\""
                + UUID.randomUUID() + "\",\"score\":42,\"qualifiedAt\":\"2026-08-23T10:00:00Z\"}");
        mort.setContentType("application/json");
        mort.setTypeId(QualifiedLeadMessage.class.getName());
        mort.setStatus(DeadLetterStatus.PENDING);
        return mort;
    }
}

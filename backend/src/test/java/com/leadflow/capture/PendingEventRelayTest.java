package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * La methode est appelee directement plutot que d'attendre l'ordonnanceur : un test qui
 * dort pour laisser passer une periode est lent et instable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PendingEventRelayTest {

    @Autowired private PendingEventRelay relais;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RabbitTemplate rabbitTemplate;

    private UUID clientId;

    @BeforeEach
    void preparer() {
        rawLeadEventRepository.deleteAll();

        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        clientId = clientRepository.saveAndFlush(client).getId();

        while (rabbitTemplate.receive(RabbitMQConfig.LEADS_QUEUE, 200) != null) {
            // purge
        }
    }

    private RawLeadEvent evenement(RawLeadEventStatus statut, long ageSecondes) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature("t=" + Instant.now().getEpochSecond() + ",v1=" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now().minusSeconds(ageSecondes));
        evenement.setStatus(statut);
        return rawLeadEventRepository.saveAndFlush(evenement);
    }

    @Test
    void republieUnEvenementRecuMaisJamaisPublie() {
        RawLeadEvent oublie = evenement(RawLeadEventStatus.RECEIVED, 600);

        relais.republieLesEnAttente();

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.LEADS_QUEUE, 5000);
        assertThat(recu).isInstanceOf(CapturedLeadMessage.class);
        assertThat(((CapturedLeadMessage) recu).eventId()).isEqualTo(oublie.getId());
        assertThat(rawLeadEventRepository.findById(oublie.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.PUBLISHED);
    }

    @Test
    void republieUnEvenementEnEchec() {
        RawLeadEvent echoue = evenement(RawLeadEventStatus.FAILED, 600);

        relais.republieLesEnAttente();

        assertThat(rawLeadEventRepository.findById(echoue.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.PUBLISHED);
    }

    @Test
    void ignoreUnEvenementTropRecent() {
        // Le filet ne doit jamais doubler une publication en cours : relay-after est
        // volontairement plus long que la duree d'une publication normale.
        RawLeadEvent recent = evenement(RawLeadEventStatus.RECEIVED, 0);

        relais.republieLesEnAttente();

        assertThat(rawLeadEventRepository.findById(recent.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.RECEIVED);
        assertThat(rabbitTemplate.receive(RabbitMQConfig.LEADS_QUEUE, 500)).isNull();
    }

    @Test
    void ignoreUnEvenementDejaPublie() {
        RawLeadEvent publie = evenement(RawLeadEventStatus.PUBLISHED, 600);

        relais.republieLesEnAttente();

        assertThat(rabbitTemplate.receive(RabbitMQConfig.LEADS_QUEUE, 500)).isNull();
        assertThat(rawLeadEventRepository.findById(publie.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.PUBLISHED);
    }
}

package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.CapturedLeadMessage;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Le seul test qui traverse le broker. La suite retire le consommateur — sans quoi il
 * volerait aux tests de la couche capture les messages qu'ils viennent de publier — donc
 * cette classe le rallume par propriete, dans son propre contexte.
 *
 * <p>Ce qui est verifie ici n'est pas la qualification, deja couverte sans broker, mais le
 * cablage : un message publie sur {@code lead.captured} aboutit bien a un lead en base.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.qualification.listener.enabled=true",
        "leadflow.intent.gemini.enabled=false"})
class LeadQualificationListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;

    /** La base est partagee : laisser un lead derriere soi ferait echouer la classe suivante. */
    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
    }

    @Test
    void qualifieUnLeadRecuParLaFile() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        UUID clientId = clientRepository.save(client).getId();

        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(clientId);
        brut.setSource("formulaire-devis");
        brut.setPayload(new HashMap<>(Map.of(
                "email", "karim@acme.test", "message", "Je veux un devis pour 50 unites")));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        RawLeadEvent enregistre = rawLeadEventRepository.save(brut);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.LEADS_ROUTING_KEY,
                new CapturedLeadMessage(
                        enregistre.getId(),
                        clientId,
                        enregistre.getSource(),
                        Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(leadRepository.findAll())
                        .singleElement()
                        .satisfies(lead -> {
                            assertThat(lead.getRawEventId()).isEqualTo(enregistre.getId());
                            assertThat(lead.getStatus()).isEqualTo(LeadStatus.QUALIFIED);
                        }));
    }
}

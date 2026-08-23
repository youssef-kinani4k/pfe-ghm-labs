package com.leadflow.monitoring.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.QualifiedLeadMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "leadflow.monitoring.stream.listener.enabled=true")
class PipelineEventListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadStreamBroadcaster diffuseur;

    @Test
    void unMessageDuPipelineAtteintUnAbonneSansEtreVoleAuConsommateurMetier() {
        SseEmitter emetteur = diffuseur.abonne();
        emetteur.onCompletion(() -> {});

        UUID leadId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.QUALIFIED_ROUTING_KEY,
                new QualifiedLeadMessage(leadId, UUID.randomUUID(), 42, Instant.now()));

        // La file metier a bien recu sa copie : la preuve qu'un DirectExchange livre a
        // toutes les files liees a la cle, et que l'observateur ne vole rien.
        Message copieMetier = rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000);
        assertThat(copieMetier).isNotNull();

        await().atMost(Duration.ofSeconds(10))
                .until(() -> diffuseur.dernierEvenementDiffuse() != null);
        assertThat(diffuseur.dernierEvenementDiffuse().leadId()).isEqualTo(leadId);
    }

    /**
     * Hors requete HTTP, {@code complete()} ne declenche pas les rappels {@code
     * onCompletion} — ceux-ci sont invoques par l'infrastructure asynchrone de Spring MVC.
     * Le filet qui compte vraiment est donc l'autre : une diffusion vers un emetteur mort
     * le retire du registre, sans quoi chaque diffusion suivante echouerait.
     */
    @Test
    void unEmetteurFermeEstRetireDuRegistre() {
        SseEmitter emetteur = diffuseur.abonne();
        assertThat(diffuseur.nombreDAbonnes()).isPositive();

        emetteur.complete();
        diffuseur.maintientLesConnexions();

        await().atMost(Duration.ofSeconds(5))
                .until(() -> diffuseur.nombreDAbonnes() == 0);
    }
}

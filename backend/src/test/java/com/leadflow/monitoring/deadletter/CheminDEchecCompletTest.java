package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.RoutedLeadMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Le seul test qui parcourt le chemin d'echec en entier : un consommateur qui leve, les
 * trois tentatives, le recoverer, la DLX, la DLQ, le journal. Sans lui, rien ne garantit
 * que le motif affiche a l'ecran est bien celui de l'exception levee.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, CheminDEchecCompletTest.ConsommateurQuiEchoue.class})
@TestPropertySource(properties = {
        "leadflow.monitoring.deadletter.listener.enabled=true",
        // Trois tentatives rapides : le backoff par defaut ferait durer le test.
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=1"})
class CheminDEchecCompletTest {

    static final String MOTIF = "Aucun commercial actif pour ce client";

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private DeadLetterRepository repository;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
    }

    @Test
    void troisEchecsProduisentUneLignePortantLeMessageDeLException() {
        UUID leadId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.ROUTED_ROUTING_KEY,
                new RoutedLeadMessage(leadId, UUID.randomUUID(), UUID.randomUUID(),
                        Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findAll()).singleElement().satisfies(mort -> {
                    assertThat(mort.getFailureReason()).contains(MOTIF);
                    assertThat(mort.getRoutingKey())
                            .isEqualTo(RabbitMQConfig.ROUTED_ROUTING_KEY);
                    assertThat(mort.getLeadId()).isEqualTo(leadId);
                }));
    }

    @TestConfiguration
    static class ConsommateurQuiEchoue {

        @RabbitListener(queues = RabbitMQConfig.ROUTED_QUEUE)
        void recoit(RoutedLeadMessage message) {
            throw new IllegalStateException(MOTIF);
        }
    }
}

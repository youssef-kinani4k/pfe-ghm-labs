package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "leadflow.monitoring.deadletter.listener.enabled=true")
class DeadLetterListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private DeadLetterRepository repository;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
    }

    @Test
    void journaliseUnMessageMortAvecSaCauseEtSonTypeId() {
        UUID leadId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        String corps = """
                {"leadId":"%s","clientId":"%s","score":42,"qualifiedAt":"2026-08-23T10:00:00Z"}
                """.formatted(leadId, clientId);

        MessageProperties proprietes = new MessageProperties();
        proprietes.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        proprietes.setHeader("__TypeId__", "com.leadflow.qualification.QualifiedLeadMessage");
        proprietes.setHeader("x-original-routingKey", RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        proprietes.setHeader("x-first-death-queue", RabbitMQConfig.QUALIFIED_QUEUE);
        proprietes.setHeader("x-exception-message", "Aucun commercial actif pour ce client");
        Message message = MessageBuilder.withBody(corps.getBytes()).andProperties(proprietes)
                .build();

        rabbitTemplate.send(RabbitMQConfig.DLX_EXCHANGE, RabbitMQConfig.DLQ_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findAll()).singleElement().satisfies(mort -> {
                assertThat(mort.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
                assertThat(mort.getRoutingKey())
                        .isEqualTo(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
                assertThat(mort.getTypeId())
                        .isEqualTo("com.leadflow.qualification.QualifiedLeadMessage");
                assertThat(mort.getFailureReason())
                        .contains("Aucun commercial actif pour ce client");
                assertThat(mort.getLeadId()).isEqualTo(leadId);
                assertThat(mort.getPayload()).contains(leadId.toString());
            });
        });
    }

    @Test
    void journaliseQuandMemeUneChargeUtileIllisible() {
        MessageProperties proprietes = new MessageProperties();
        proprietes.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = MessageBuilder.withBody("{tronque".getBytes())
                .andProperties(proprietes).build();

        rabbitTemplate.send(RabbitMQConfig.DLX_EXCHANGE, RabbitMQConfig.DLQ_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findAll()).singleElement().satisfies(mort -> {
                // Ce n'est pas une erreur du journal : la ligne est ecrite avec ce qu'on a,
                // et failure_reason dit ce qu'on n'a pas su lire.
                assertThat(mort.getPayload()).isEqualTo("{tronque");
                assertThat(mort.getLeadId()).isNull();
                assertThat(mort.getRoutingKey()).isNotBlank();
                assertThat(mort.getFailureReason()).contains("illisible");
            });
        });
    }
}

package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.deadletter.DeadLetter;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.monitoring.dto.QueueView;
import com.leadflow.monitoring.dto.QueuesView;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QueueServiceTest {

    @Autowired private QueueService service;
    @Autowired private DeadLetterRepository morts;

    @AfterEach
    void nettoie() {
        morts.deleteAll();
    }

    @Test
    void listeLesSixFilesDuPipeline() {
        QueuesView vue = service.etatDesFiles();

        assertThat(vue.queues()).extracting(QueueView::name).containsExactlyInAnyOrder(
                RabbitMQConfig.LEADS_QUEUE,
                RabbitMQConfig.QUALIFIED_QUEUE,
                RabbitMQConfig.ROUTED_QUEUE,
                RabbitMQConfig.REASSIGNED_QUEUE,
                RabbitMQConfig.NOTIFY_QUEUE,
                RabbitMQConfig.DLQ_QUEUE);
    }

    @Test
    void remonteProfondeurEtNombreDeConsommateurs() {
        QueueView captees = service.etatDesFiles().queues().stream()
                .filter(file -> file.name().equals(RabbitMQConfig.LEADS_QUEUE))
                .findFirst()
                .orElseThrow();

        assertThat(captees.reachable()).isTrue();
        assertThat(captees.messageCount()).isGreaterThanOrEqualTo(0);
        assertThat(captees.consumerCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void compteLesMortsEnAttenteEnBase() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue(RabbitMQConfig.QUALIFIED_QUEUE);
        mort.setRoutingKey(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        mort.setPayload("{}");
        mort.setLeadId(UUID.randomUUID());
        mort.setStatus(DeadLetterStatus.PENDING);
        morts.saveAndFlush(mort);

        assertThat(service.etatDesFiles().pendingDeadLetters()).isEqualTo(1L);
    }
}

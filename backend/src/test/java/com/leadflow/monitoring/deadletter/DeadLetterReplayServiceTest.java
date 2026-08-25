package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.QualifiedLeadMessage;
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
    @Autowired private RabbitTemplate rabbitTemplate;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
        // La file est partagee par la suite : la vider evite qu'un message rejoue ici soit
        // lu par un autre test.
        while (rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 100) != null) {
            // on vide
        }
    }

    @Test
    void republieLesOctetsDOrigineAvecLeMemeTypeId() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.rejoue(mort.getId(), "operateur");

        Message republie = rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000);
        assertThat(republie).isNotNull();
        assertThat(new String(republie.getBody())).isEqualTo(mort.getPayload());
        assertThat(republie.getMessageProperties().getHeaders())
                .containsEntry("__TypeId__", QualifiedLeadMessage.class.getName());
    }

    @Test
    void marqueLaLigneRejoueeAvecLeNomDeLOperateur() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.rejoue(mort.getId(), "camille");

        assertThat(repository.findById(mort.getId())).hasValueSatisfying(relue -> {
            assertThat(relue.getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
            assertThat(relue.getReplayedBy()).isEqualTo("camille");
            assertThat(relue.getReplayedAt()).isNotNull();
        });
    }

    @Test
    void unSecondRejeuEstRefuse() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());
        service.rejoue(mort.getId(), "camille");

        assertThatThrownBy(() -> service.rejoue(mort.getId(), "camille"))
                .isInstanceOf(DejaTraiteException.class);
    }

    @Test
    void ecarterMarqueSansRienRepublier() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.ecarte(mort.getId(), "camille");

        assertThat(repository.findById(mort.getId()).orElseThrow().getStatus())
                .isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 500)).isNull();
    }

    @Test
    void mortInconnueRendUneRessourceIntrouvable() {
        assertThatThrownBy(() -> service.rejoue(UUID.randomUUID(), "camille"))
                .isInstanceOf(com.leadflow.common.RessourceIntrouvableException.class);
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

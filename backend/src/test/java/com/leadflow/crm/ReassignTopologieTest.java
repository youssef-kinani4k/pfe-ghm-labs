package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.LeadReassignedMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * La file de reaffectation et son contrat, eprouves sur un vrai broker.
 *
 * <p>Le second test est celui qui compte : le convertisseur ne fait confiance qu'a une liste
 * blanche de paquets, et un contrat absent de PAQUETS_DE_CONFIANCE se refuse a la lecture
 * sans dire pourquoi.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReassignTopologieTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void videLaFile() {
        // Purge BLOQUANTE : la surcharge avec noWait rend la main avant la fin, et dans la
        // suite complete elle emporterait le message qu'on vient de publier.
        rabbitAdmin.purgeQueue(RabbitMQConfig.REASSIGNED_QUEUE);
    }

    @Test
    void laFileExisteEtPorteLeBonNom() {
        QueueInformation info = rabbitAdmin.getQueueInfo(RabbitMQConfig.REASSIGNED_QUEUE);

        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("leadflow.leads.reassigned");
    }

    @Test
    void leMessageSeDeserialiseEnLeadReassignedMessage() {
        UUID leadId = UUID.randomUUID();
        UUID ancien = UUID.randomUUID();
        UUID nouveau = UUID.randomUUID();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.REASSIGNED_ROUTING_KEY,
                new LeadReassignedMessage(
                        leadId, UUID.randomUUID(), ancien, nouveau, Instant.now()));

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.REASSIGNED_QUEUE, 5000);

        assertThat(recu).isInstanceOf(LeadReassignedMessage.class);
        LeadReassignedMessage message = (LeadReassignedMessage) recu;
        assertThat(message.leadId()).isEqualTo(leadId);
        assertThat(message.previousSalesRepId()).isEqualTo(ancien);
        assertThat(message.newSalesRepId()).isEqualTo(nouveau);
    }
}

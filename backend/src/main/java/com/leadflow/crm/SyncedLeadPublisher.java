package com.leadflow.crm;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publie la fin du parcours. Sans elle, le flux du dashboard montrerait un lead fige en
 * {@code ROUTED} jusqu'au prochain rechargement : il raterait la fin de l'histoire qu'il
 * raconte, {@code CrmSyncListener} ecrivant {@code SYNCED} puis se taisant.
 *
 * <p>L'echec de publication est logue et jamais relance : le lead est synchronise, et faire
 * echouer le consommateur renverrait en DLQ un message deja traite avec succes. Meme regle
 * qu'en F3 et F4.
 */
@Component
public class SyncedLeadPublisher {

    private static final Logger log = LoggerFactory.getLogger(SyncedLeadPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public SyncedLeadPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(UUID leadId, UUID clientId, String providerId) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.SYNCED_ROUTING_KEY,
                    new SyncedLeadMessage(leadId, clientId, providerId, Instant.now()));
        } catch (AmqpException echec) {
            log.warn("Publication de lead.synced pour {} en echec", leadId, echec);
        }
    }
}

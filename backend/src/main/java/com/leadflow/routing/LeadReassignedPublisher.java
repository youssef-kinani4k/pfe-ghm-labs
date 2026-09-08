package com.leadflow.routing;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Annonce a l'ERP qu'un lead a change de responsable.
 *
 * <p>Appel direct apres le commit, comme {@link RoutedLeadPublisher} et non
 * {@code @TransactionalEventListener} : {@link ReattributionService} n'etant pas
 * transactionnel, un tel listener serait silencieusement ignore.
 *
 * <p><b>L'echec de publication n'est jamais relance.</b> La reattribution a eu lieu et elle
 * est journalisee ; faire echouer l'appel HTTP apres coup dirait a l'operateur que son
 * geste a echoue alors qu'il a reussi.
 *
 * <p><b>Il n'y a pas de filet de republication</b>, contrairement a {@link RoutedLeadRelay} :
 * un lead reattribue ne porte aucun etat que le filet pourrait balayer — il reste
 * {@code SYNCED}, exactement comme un lead dont la propagation a reussi. Meme dette assumee
 * que sur {@code lead.qualified}, et elle se paierait pour les trois cles d'un coup.
 */
@Component
public class LeadReassignedPublisher {

    private static final Logger log = LoggerFactory.getLogger(LeadReassignedPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public LeadReassignedPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(UUID leadId, UUID clientId, UUID ancien, UUID nouveau) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.REASSIGNED_ROUTING_KEY,
                    new LeadReassignedMessage(leadId, clientId, ancien, nouveau, Instant.now()));
        } catch (AmqpException echec) {
            log.warn("Publication de la reaffectation du lead {} en echec", leadId, echec);
        }
    }
}

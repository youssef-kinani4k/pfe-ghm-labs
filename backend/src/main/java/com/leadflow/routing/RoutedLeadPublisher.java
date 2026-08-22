package com.leadflow.routing;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.Lead;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publie le lead attribue vers la synchronisation ERP.
 *
 * <p>Appel direct apres le retour de {@link RoutedLeadWriter#attribue}, donc apres le
 * commit — et non {@code @TransactionalEventListener(AFTER_COMMIT)} comme en F2 :
 * l'orchestrateur n'etant pas transactionnel, un tel listener serait <b>silencieusement
 * ignore</b> et le message ne partirait jamais.
 *
 * <p><b>Dette assumee : aucun filet de republication.</b> Si l'envoi echoue, le lead reste
 * {@code ROUTED} — rien n'est perdu, mais rien ne le republie. Le balayage symetrique
 * ({@code findByStatusAndCreatedAtBefore(ROUTED, seuil)}) est desormais bornable puisque le
 * lead quitte cet etat pour {@code SYNCED} ; il est reporte a F6, ou il rejoint le rejeu
 * manuel depuis la DLQ.
 */
@Component
public class RoutedLeadPublisher {

    private static final Logger log = LoggerFactory.getLogger(RoutedLeadPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RoutedLeadPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(Lead lead) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.ROUTED_ROUTING_KEY,
                    new RoutedLeadMessage(
                            lead.getId(),
                            lead.getClientId(),
                            lead.getAssignedSalesRepId(),
                            Instant.now()));
        } catch (AmqpException echec) {
            // Ne jamais relancer : le lead est attribue, et faire echouer le consommateur
            // renverrait en DLQ un message deja traite avec succes.
            log.warn("Publication du lead route {} en echec", lead.getId(), echec);
        }
    }
}

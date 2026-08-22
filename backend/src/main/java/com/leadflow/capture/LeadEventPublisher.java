package com.leadflow.capture;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publie apres le commit de la capture, jamais avant : un message parti trop tot
 * designerait une ligne que le consommateur ne trouverait pas.
 *
 * <p>{@code REQUIRES_NEW} parce qu'a ce moment la transaction de capture est deja commitee
 * et qu'il n'y en a plus aucune : le changement de statut a besoin de la sienne. Meme
 * raisonnement que {@code CrmSyncTraceWriter} en F5.
 *
 * <p>Les deux annotations sont sur la meme methode a dessein : le filet de republication
 * appelle {@link #publie} directement, et passe donc par le proxy comme le listener.
 */
@Component
public class LeadEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LeadEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RawLeadEventRepository rawLeadEventRepository;

    public LeadEventPublisher(
            RabbitTemplate rabbitTemplate, RawLeadEventRepository rawLeadEventRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.rawLeadEventRepository = rawLeadEventRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publie(LeadCapturedEvent evenement) {
        RawLeadEvent ligne = rawLeadEventRepository.findById(evenement.eventId()).orElse(null);
        if (ligne == null || ligne.getStatus() == RawLeadEventStatus.PUBLISHED) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.LEADS_ROUTING_KEY,
                    new CapturedLeadMessage(
                            evenement.eventId(),
                            evenement.clientId(),
                            evenement.source(),
                            evenement.receivedAt()));
            ligne.setStatus(RawLeadEventStatus.PUBLISHED);
            ligne.setPublishedAt(Instant.now());
            ligne.setFailureReason(null);
        } catch (AmqpException echec) {
            // La ligne reste en base : c'est le filet qui la reprendra. Ne jamais relancer
            // ici, l'appelant HTTP a deja recu son 202 et la transaction est close.
            log.warn("Publication de l'evenement {} en echec", evenement.eventId(), echec);
            ligne.setStatus(RawLeadEventStatus.FAILED);
            ligne.setFailureReason(echec.getMessage());
        }
        rawLeadEventRepository.save(ligne);
    }
}

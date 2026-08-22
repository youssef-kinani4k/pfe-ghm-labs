package com.leadflow.qualification;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publie le lead qualifie vers le routage.
 *
 * <p>Appel direct et non {@code @TransactionalEventListener(AFTER_COMMIT)} comme en F2 :
 * ici l'ecriture a deja ete commitee par {@link LeadWriter} dans sa transaction
 * {@code REQUIRES_NEW}, et l'orchestrateur n'est pas transactionnel. Un
 * {@code @TransactionalEventListener} publie hors de toute transaction serait
 * <b>silencieusement ignore</b> : le message ne partirait jamais, sans la moindre erreur.
 *
 * <p><b>Dette assumee : aucun filet de republication.</b> Si l'envoi echoue, le lead reste
 * en base avec {@code status = QUALIFIED} — rien n'est perdu, mais rien ne le republie. Le
 * filet symetrique de {@code PendingEventRelay} consisterait a rebalayer les leads
 * {@code QUALIFIED} plus vieux que N minutes ; il est impossible tant que F4 n'existe pas,
 * puisque aucun lead ne quitte jamais cet etat et que le balayage republierait la table
 * entiere en boucle. C'est F4, qui fait passer le lead a {@code ROUTED}, qui rendra ce
 * filet possible et borne : {@code findByStatusAndCreatedAtBefore(QUALIFIED, seuil)}.
 */
@Component
public class QualifiedLeadPublisher {

    private static final Logger log = LoggerFactory.getLogger(QualifiedLeadPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public QualifiedLeadPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(Lead lead) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.QUALIFIED_ROUTING_KEY,
                    new QualifiedLeadMessage(
                            lead.getId(), lead.getClientId(), lead.getScore(), Instant.now()));
        } catch (AmqpException echec) {
            // Ne jamais relancer : le lead est ecrit, et faire echouer le consommateur
            // renverrait en DLQ un evenement deja traite avec succes.
            log.warn("Publication du lead qualifie {} en echec", lead.getId(), echec);
        }
    }
}

package com.leadflow.monitoring.stream;

import com.leadflow.capture.CapturedLeadMessage;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.SyncedLeadMessage;
import com.leadflow.qualification.QualifiedLeadMessage;
import com.leadflow.routing.RoutedLeadMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Traduit les contrats de file du pipeline en evenements de flux. Il n'ecrit rien et ne lit
 * aucune table : c'est l'observateur au sens strict.
 *
 * <p>La conversion est faite ici, a la main, et non par la signature de la methode : un
 * parametre {@code Object} ne declenche aucune conversion — Spring AMQP considere que le
 * {@link Message} brut satisfait deja le type demande — et le {@code switch} tomberait
 * systematiquement dans son cas par defaut. Une signature typee, elle, obligerait a un
 * consommateur par contrat sur une file qui les porte tous.
 *
 * <p>Un message illisible est logue et acquitte. La file d'observation n'a pas de DLX : le
 * remettre en file ferait tourner une boucle chaude pour un simple probleme d'affichage.
 *
 * <p>Bean conditionnel comme les quatre autres consommateurs du projet, et retire dans la
 * suite de tests sauf la ou elle l'eprouve.
 */
@Component
@ConditionalOnProperty(
        name = "leadflow.monitoring.stream.listener.enabled", matchIfMissing = true)
public class PipelineEventListener {

    private static final Logger log = LoggerFactory.getLogger(PipelineEventListener.class);

    private final LeadStreamBroadcaster diffuseur;
    private final MessageConverter converteur;

    public PipelineEventListener(LeadStreamBroadcaster diffuseur, MessageConverter converteur) {
        this.diffuseur = diffuseur;
        this.converteur = converteur;
    }

    @RabbitListener(queues = RabbitMQConfig.MONITORING_QUEUE)
    public void recoit(Message message) {
        Object contrat;
        try {
            contrat = converteur.fromMessage(message);
        } catch (RuntimeException illisible) {
            log.warn("Evenement d'observation illisible, ignore", illisible);
            return;
        }

        switch (contrat) {
            // La capture ne connait pas encore de lead : elle porte l'evenement brut, et
            // le flux n'a d'identifiant de lead qu'a partir de la qualification.
            case CapturedLeadMessage capte -> diffuseur.diffuseLead(new StreamEvent(
                    null, capte.clientId(), "CAPTURED", null, null, capte.receivedAt()));
            case QualifiedLeadMessage qualifie -> diffuseur.diffuseLead(new StreamEvent(
                    qualifie.leadId(), qualifie.clientId(), "QUALIFIED", qualifie.score(),
                    null, qualifie.qualifiedAt()));
            case RoutedLeadMessage route -> diffuseur.diffuseLead(new StreamEvent(
                    route.leadId(), route.clientId(), "ROUTED", null, route.salesRepId(),
                    route.routedAt()));
            case SyncedLeadMessage synchronise -> diffuseur.diffuseLead(new StreamEvent(
                    synchronise.leadId(), synchronise.clientId(), "SYNCED", null, null,
                    synchronise.syncedAt()));
            // Un contrat de file inconnu ne fait pas tomber l'observateur : une nouvelle
            // etape du pipeline ne doit pas casser l'ecran avant d'y etre cablee.
            default -> log.debug("Contrat de file non diffuse : {}",
                    contrat == null ? "null" : contrat.getClass());
        }
    }
}

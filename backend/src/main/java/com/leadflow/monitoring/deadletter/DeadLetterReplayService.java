package com.leadflow.monitoring.deadletter;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.LeadAction;
import com.leadflow.routing.LeadActionJournal;
import com.leadflow.routing.LeadActionOutcome;
import com.leadflow.routing.LeadActionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rejeu unitaire d'un message mort, et mise a l'ecart.
 *
 * <p>Le message republie porte <b>les octets d'origine</b>, le {@code content-type} et le
 * {@code __TypeId__} d'origine : sans ce dernier, le convertisseur ne saurait pas dans
 * quelle classe deserialiser a l'arrivee. Rien n'est reconstruit — republier un objet
 * re-serialise ferait du rejeu autre chose que ce qui a echoue.
 *
 * <p>Aucun rejeu de masse. Un rejeu de masse sur un incident non compris multiplie
 * l'incident ; l'ecran boucle sur une selection explicite s'il le faut.
 *
 * <p><b>Rejouer un {@code lead.qualified} decale la rotation du tour de role</b>, qui n'est
 * pas idempotent : le lead peut changer de commercial. C'est signale a l'ecran, pas empeche
 * — l'empecher demanderait de rouvrir une decision de F4.
 *
 * <p>L'ordre est delibere : on publie d'abord, on marque ensuite. Marquer avant publierait
 * un {@code REPLAYED} qui n'a rien republie si le broker refuse ; dans l'ordre choisi, un
 * echec de marquage laisse une ligne {@code PENDING} pour un message pourtant reparti, ce
 * qui produit au pire un rejeu en double — que la file, elle, sait absorber.
 */
@Service
public class DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

    private final DeadLetterRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final LeadActionJournal journal;

    public DeadLetterReplayService(
            DeadLetterRepository repository, RabbitTemplate rabbitTemplate,
            LeadActionJournal journal) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.journal = journal;
    }

    @Transactional
    public void rejoue(UUID id, String operateur, String motif) {
        DeadLetter mort = enAttente(id);

        MessageProperties proprietes = new MessageProperties();
        if (mort.getContentType() != null) {
            proprietes.setContentType(mort.getContentType());
        }
        if (mort.getTypeId() != null) {
            proprietes.setHeader("__TypeId__", mort.getTypeId());
        }
        // Trace du rejeu dans le message lui-meme : un consommateur qui journalise verra
        // qu'il traite un rejeu et non un premier passage.
        proprietes.setHeader("x-leadflow-replay-of", mort.getId().toString());

        Message message = MessageBuilder
                .withBody(mort.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(proprietes)
                .build();

        try {
            rabbitTemplate.send(
                    RabbitMQConfig.LEADS_EXCHANGE, mort.getRoutingKey(), message);
        } catch (AmqpException echec) {
            journalise(mort, LeadActionType.REJEU, operateur, motif,
                    LeadActionOutcome.ECHEC, echec.getMessage());
            throw new RejeuIndisponibleException(
                    "Broker injoignable, la mort reste en attente", echec);
        }

        mort.setStatus(DeadLetterStatus.REPLAYED);
        mort.setReplayedAt(Instant.now());
        mort.setReplayedBy(operateur);
        repository.saveAndFlush(mort);
        journalise(mort, LeadActionType.REJEU, operateur, motif,
                LeadActionOutcome.SUCCES, null);
        log.info("Mort {} rejouee sur {} par {}", id, mort.getRoutingKey(), operateur);
    }

    @Transactional
    public void ecarte(UUID id, String operateur, String motif) {
        DeadLetter mort = enAttente(id);
        mort.setStatus(DeadLetterStatus.DISCARDED);
        mort.setReplayedAt(Instant.now());
        mort.setReplayedBy(operateur);
        repository.saveAndFlush(mort);
        journalise(mort, LeadActionType.ECART, operateur, motif,
                LeadActionOutcome.SUCCES, null);
    }

    /**
     * Une mort dont on n'a pas su tirer de {@code leadId} n'est pas journalisee :
     * {@code lead_action.lead_id} porte une cle etrangere non nulle, et inventer une
     * reference ferait echouer l'ecriture exactement quand le message est le plus abime.
     */
    private void journalise(
            DeadLetter mort,
            LeadActionType type,
            String operateur,
            String motif,
            LeadActionOutcome issue,
            String detail) {
        if (mort.getLeadId() == null) {
            return;
        }
        LeadAction action = new LeadAction();
        action.setLeadId(mort.getLeadId());
        action.setAction(type);
        action.setActor(operateur);
        action.setReason(motif);
        action.setDeadLetterId(mort.getId());
        action.setOutcome(issue);
        action.setDetail(detail);
        journal.enregistre(action);
    }

    private DeadLetter enAttente(UUID id) {
        DeadLetter mort = repository.findById(id).orElseThrow(
                () -> new RessourceIntrouvableException("Mort inconnue : " + id));
        if (mort.getStatus() != DeadLetterStatus.PENDING) {
            throw new DejaTraiteException(
                    "Cette mort est deja " + mort.getStatus() + " : rien a refaire");
        }
        return mort;
    }
}

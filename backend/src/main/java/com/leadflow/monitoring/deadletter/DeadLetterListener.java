package com.leadflow.monitoring.deadletter;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.dto.DeadLetterView;
import com.leadflow.monitoring.stream.LeadStreamBroadcaster;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consommateur de la DLQ : chaque mort devient une ligne du journal, puis est acquittee.
 *
 * <p>Il recoit un {@link Message} <b>brut</b> et jamais un objet converti : la DLQ contient
 * precisement ce qui a echoue, y compris de l'illisible, et faire tourner Jackson dessus
 * reproduirait l'echec qu'on essaie de consigner.
 *
 * <p>Les deux facons de rater un message ne se traitent pas pareil. Une <b>charge utile
 * inexploitable</b> n'est pas une erreur du journal : la ligne est ecrite avec ce qu'on a,
 * le motif dit ce qu'on n'a pas su lire, et le message est acquitte. Une <b>base
 * indisponible</b>, elle, fait remonter l'exception : la fabrique dediee remet le message
 * dans la DLQ, qui n'a aucune DLX pour le rattraper si on l'acquittait.
 */
@Component
@ConditionalOnProperty(
        name = "leadflow.monitoring.deadletter.listener.enabled", matchIfMissing = true)
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final DeadLetterJournal journal;
    private final ObjectMapper mapper;
    private final LeadStreamBroadcaster diffuseur;

    public DeadLetterListener(
            DeadLetterJournal journal, ObjectMapper mapper, LeadStreamBroadcaster diffuseur) {
        this.journal = journal;
        this.mapper = mapper;
        this.diffuseur = diffuseur;
    }

    @RabbitListener(
            queues = RabbitMQConfig.DLQ_QUEUE,
            containerFactory = "deadLetterListenerContainerFactory")
    public void recoit(Message message) {
        DeadLetter mort = new DeadLetter();
        String corps = new String(message.getBody(), StandardCharsets.UTF_8);
        mort.setPayload(corps);
        mort.setOriginQueue(entete(message, "x-first-death-queue", "inconnue"));
        mort.setRoutingKey(entete(message, "x-original-routingKey", "inconnue"));
        mort.setContentType(message.getMessageProperties().getContentType());
        mort.setTypeId(entete(message, "__TypeId__", null));
        mort.setStatus(DeadLetterStatus.PENDING);

        StringBuilder motif = new StringBuilder();
        String exception = entete(message, "x-exception-message", null);
        if (exception != null) {
            motif.append(exception);
        }
        motif.append(lisIdentifiants(corps, mort));

        mort.setFailureReason(motif.isEmpty() ? "Cause inconnue" : motif.toString());
        DeadLetter ecrite = journal.enregistre(mort);
        // Le flux ne repasse pas par le broker : c'est le meme processus.
        diffuseur.diffuseMort(vue(ecrite));
        log.info("Mort journalisee : file {}, cle {}", mort.getOriginQueue(), mort.getRoutingKey());
    }

    /** Vue maigre : {@code clientName} reste nul, l'ecran rechargeant la liste au clic. */
    private DeadLetterView vue(DeadLetter mort) {
        return new DeadLetterView(
                mort.getId(),
                mort.getOriginQueue(),
                mort.getRoutingKey(),
                mort.getClientId(),
                null,
                mort.getLeadId(),
                mort.getFailureReason(),
                mort.getDeadAt(),
                mort.getStatus(),
                mort.getReplayedAt(),
                mort.getReplayedBy(),
                mort.getPayload(),
                null);
    }

    /**
     * Tente de tirer {@code leadId} et {@code clientId} du corps. L'echec de lecture est
     * une information a consigner, pas une raison de perdre le message : tous nos contrats
     * de file portent ces deux champs, donc ne pas les trouver dit deja quelque chose.
     */
    private String lisIdentifiants(String corps, DeadLetter mort) {
        try {
            JsonNode noeud = mapper.readTree(corps);
            mort.setLeadId(uuid(noeud, "leadId"));
            mort.setClientId(uuid(noeud, "clientId"));
            return "";
        } catch (Exception illisible) {
            return " [charge utile illisible : " + illisible.getMessage() + "]";
        }
    }

    private UUID uuid(JsonNode noeud, String champ) {
        JsonNode valeur = noeud.get(champ);
        if (valeur == null || valeur.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(valeur.asText());
        } catch (IllegalArgumentException pasUnUuid) {
            return null;
        }
    }

    private String entete(Message message, String nom, String defaut) {
        Object valeur = message.getMessageProperties().getHeaders().get(nom);
        return valeur == null ? defaut : valeur.toString();
    }
}

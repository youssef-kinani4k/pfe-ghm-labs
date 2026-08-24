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
        mort.setRoutingKey(entete(message, "x-original-routingKey", "inconnue"));
        mort.setOriginQueue(fileDOrigine(message, mort.getRoutingKey()));
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

    /**
     * File d'ou vient le message mort.
     *
     * <p>Elle se <b>deduit de la cle de routage</b>, et ne se lit pas dans un en-tete :
     * {@code RepublishMessageRecoverer} n'en pose que quatre — exchange d'origine, cle de
     * routage, message et trace d'exception. Le {@code x-first-death-queue} du protocole
     * AMQP vient du mecanisme de dead-lettering du broker, que la republication court-circuite
     * precisement pour pouvoir joindre la cause de l'echec. Le lire donnait « inconnue » sur
     * chaque ligne, et rendait sans objet le filtre par file du journal.
     *
     * <p>L'en-tete reste consulte en premier : une ligne arrivee par le dead-lettering natif
     * du broker, sans passer par le recoverer, le porte.
     */
    private static String fileDOrigine(Message message, String cleDeRoutage) {
        String entete = entete(message, "x-first-death-queue", null);
        if (entete != null) {
            return entete;
        }
        return switch (cleDeRoutage) {
            case RabbitMQConfig.LEADS_ROUTING_KEY -> RabbitMQConfig.LEADS_QUEUE;
            case RabbitMQConfig.QUALIFIED_ROUTING_KEY -> RabbitMQConfig.QUALIFIED_QUEUE;
            case RabbitMQConfig.ROUTED_ROUTING_KEY -> RabbitMQConfig.ROUTED_QUEUE;
            default -> "inconnue";
        };
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

    private static String entete(Message message, String nom, String defaut) {
        Object valeur = message.getMessageProperties().getHeaders().get(nom);
        return valeur == null ? defaut : valeur.toString();
    }
}

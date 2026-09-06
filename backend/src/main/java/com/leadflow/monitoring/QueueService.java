package com.leadflow.monitoring;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.monitoring.dto.QueueView;
import com.leadflow.monitoring.dto.QueuesView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

/**
 * Profondeur et nombre de consommateurs, lus par un {@code queue.declare} passif en AMQP.
 *
 * <p>Pas l'API de management : elle demanderait un second jeu d'identifiants, le plugin
 * correspondant, et la resolution du port 15672 dans les tests — que
 * {@code @ServiceConnection} ne cable pas.
 *
 * <p>Le nombre de consommateurs est la mesure la plus lisible d'un listener tombe : un zero
 * sur {@code leadflow.leads.qualified} explique en une seconde pourquoi plus rien n'avance.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private static final List<String> FILES = List.of(
            RabbitMQConfig.LEADS_QUEUE,
            RabbitMQConfig.QUALIFIED_QUEUE,
            RabbitMQConfig.ROUTED_QUEUE,
            RabbitMQConfig.REASSIGNED_QUEUE,
            RabbitMQConfig.NOTIFY_QUEUE,
            RabbitMQConfig.DLQ_QUEUE);

    private final RabbitAdmin admin;
    private final DeadLetterRepository morts;

    public QueueService(RabbitAdmin admin, DeadLetterRepository morts) {
        this.admin = admin;
        this.morts = morts;
    }

    public QueuesView etatDesFiles() {
        List<QueueView> vues = FILES.stream().map(this::etat).toList();
        return new QueuesView(vues, morts.countByStatus(DeadLetterStatus.PENDING));
    }

    private QueueView etat(String nom) {
        try {
            QueueInformation info = admin.getQueueInfo(nom);
            if (info == null) {
                // La file n'existe pas encore cote broker : anomalie de deploiement, pas
                // une erreur d'appel.
                return new QueueView(nom, false, 0L, 0L);
            }
            return new QueueView(nom, true, info.getMessageCount(), info.getConsumerCount());
        } catch (RuntimeException echec) {
            // Un broker injoignable ne doit pas faire echouer l'ecran qui sert a le
            // constater : la ligne sort « injoignable » et le reste s'affiche.
            log.warn("Etat de la file {} illisible", nom, echec);
            return new QueueView(nom, false, 0L, 0L);
        }
    }
}

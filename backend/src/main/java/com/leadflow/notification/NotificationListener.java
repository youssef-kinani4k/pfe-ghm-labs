package com.leadflow.notification;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.SyncedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cinquieme etape : previent le commercial du lead qui vient d'arriver chez lui.
 *
 * <p>Il ne traduit que le protocole ; tout le metier vit dans {@link NotificationService},
 * testable sans broker. Aucune exception n'est rattrapee : un echec technique doit provoquer
 * les trois tentatives puis la DLQ, et sa trace est deja ecrite en transaction propre par
 * {@link NotificationTraceWriter}, donc elle survit.
 *
 * <p>Il consomme le {@code SyncedLeadMessage} de {@code crm/} sans en definir un a lui :
 * la notification n'ajoute aucun fait a ce que la synchronisation a deja dit.
 *
 * <p>Bean conditionnel, comme les cinq autres consommateurs du projet : la suite de tests le
 * retire, un consommateur actif volant aux tests des etapes amont le message qu'ils viennent
 * de publier. Ne pas le remplacer par {@code auto-startup=false} : le cache de contextes de
 * test met un contexte en pause puis le redemarre, et {@code start()} reveille les beans
 * {@code Lifecycle} en ignorant ce reglage.
 */
@Component
@ConditionalOnProperty(name = "leadflow.notification.listener.enabled", matchIfMissing = true)
public class NotificationListener {

    private final NotificationService service;

    public NotificationListener(NotificationService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFY_QUEUE)
    public void recoit(SyncedLeadMessage message) {
        service.notifie(message.leadId());
    }
}

package com.leadflow.crm;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.RoutedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Derniere etape du pipeline : pousse le lead attribue dans l'ERP du client.
 *
 * <p>Etape distincte du routage, avec sa propre file, parce qu'un ERP injoignable est le
 * cas le plus frequent et le moins evitable. Avec un seul consommateur, il renverrait en DLQ
 * un message dont le rejeu reattribuerait le lead — or le tour de role n'est pas idempotent.
 * Ici, la DLQ ne contient que ce qui a reellement echoue, et le rejeu s'appuie sur
 * {@code CrmSyncState}, concu pour cela depuis F5.
 *
 * <p>Aucune exception n'est rattrapee : {@code CrmSyncException} doit provoquer les trois
 * tentatives puis la DLQ. La ligne {@code crm_sync_attempt} en echec est deja ecrite par
 * {@link CrmSyncService}, en transaction propre, donc elle survit.
 *
 * <p>Bean conditionnel pour la meme raison que les autres consommateurs du projet : la suite
 * de tests le retire, et un contexte remis en marche par le cache de tests redemarrerait ses
 * beans {@code Lifecycle} en ignorant {@code auto-startup}.
 */
@Component
@ConditionalOnProperty(name = "leadflow.crm.listener.enabled", matchIfMissing = true)
public class CrmSyncListener {

    private final CrmSyncService service;
    private final SyncedLeadWriter writer;

    public CrmSyncListener(CrmSyncService service, SyncedLeadWriter writer) {
        this.service = service;
        this.writer = writer;
    }

    @RabbitListener(queues = RabbitMQConfig.ROUTED_QUEUE)
    public void recoit(RoutedLeadMessage message) {
        service.synchronise(message.leadId());
        writer.marqueSynchronise(message.leadId());
    }
}

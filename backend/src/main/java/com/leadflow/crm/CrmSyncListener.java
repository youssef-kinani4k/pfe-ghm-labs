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
 * <p><b>Une synchronisation reussie suivie d'une ecriture de statut en echec ne pousse
 * rien deux fois.</b> L'exception remonte, le message est rejoue, et {@code CrmSyncService}
 * retrouve ses references dans {@code CrmSyncState} : l'adaptateur saute les etapes deja
 * faites et l'ERP n'est pas duplique. Apres trois echecs, le lead reste {@code ROUTED} avec
 * un ERP pourtant correct — situation rattrapable a la main, et que le filet de
 * republication reporte a F6 fermera.
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
    private final SyncedLeadPublisher publieur;

    public CrmSyncListener(
            CrmSyncService service, SyncedLeadWriter writer, SyncedLeadPublisher publieur) {
        this.service = service;
        this.writer = writer;
        this.publieur = publieur;
    }

    @RabbitListener(queues = RabbitMQConfig.ROUTED_QUEUE)
    public void recoit(RoutedLeadMessage message) {
        service.synchronise(message.leadId());
        writer.marqueSynchronise(message.leadId());
        // Rien n'existait apres cette etape : sans lead.synced, le flux du dashboard
        // figerait le lead en ROUTED et raterait la fin de son parcours.
        publieur.publie(message.leadId(), message.clientId(), null);
    }
}

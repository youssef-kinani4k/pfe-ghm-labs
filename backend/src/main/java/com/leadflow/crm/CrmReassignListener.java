package com.leadflow.crm;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.LeadReassignedMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Porte la correction d'un responsable jusqu'a l'ERP.
 *
 * <p>File dediee plutot qu'un appel dans le {@code PUT} de reattribution : un ERP lent
 * ferait trainer un geste deja reussi cote LeadFlow, et un ERP eteint forcerait a choisir
 * entre echouer le geste entier ou rendre {@code 200} avec un avertissement.
 *
 * <p>Aucune exception n'est rattrapee : les trois tentatives puis la DLQ sont le
 * comportement voulu, et la ligne {@code crm_sync_attempt} en echec est deja ecrite par
 * {@link CrmReassignService} en transaction propre, donc elle survit.
 *
 * <p><b>Le rejeu depuis le journal des morts est moins risque qu'une republication de
 * {@code lead.qualified}</b>, dont le rejeu decale le tour de role. Ce n'est cependant pas
 * une idempotence garantie pour tous les ERP : {@link com.leadflow.crm.dolibarr.DolibarrConnector}
 * documente qu'aucune sonde Dolibarr ne permet de savoir si un responsable est deja lie, si
 * bien qu'un rejeu peut y tenter un doublon que l'ERP refuse — auquel cas un lead par
 * ailleurs correctement synchronise part en DLQ. Vrai en revanche pour Odoo, ou l'ecriture
 * de {@code user_id} est un simple remplacement.
 *
 * <p>Bean conditionnel comme les autres consommateurs du projet : la suite de tests le
 * retire, et un contexte remis en marche par le cache de tests redemarrerait ses beans
 * {@code Lifecycle} en ignorant {@code auto-startup}.
 */
@Component
@ConditionalOnProperty(name = "leadflow.crm.reassign.listener.enabled", matchIfMissing = true)
public class CrmReassignListener {

    private final CrmReassignService service;

    public CrmReassignListener(CrmReassignService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitMQConfig.REASSIGNED_QUEUE)
    public void recoit(LeadReassignedMessage message) {
        service.propage(message.leadId());
    }
}

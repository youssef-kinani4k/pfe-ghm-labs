package com.leadflow.crm;

import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;

/**
 * Port de sortie vers un ERP/CRM. Une implementation par fournisseur supporte.
 *
 * <p>Les implementations sont des beans Spring ; {@link CrmConnectorRegistry} les collecte
 * automatiquement. Un adaptateur doit etre idempotent autant que possible : le message
 * peut etre rejoue apres un echec partiel.
 */
public interface CrmConnector {

    /**
     * Identifiant stable du fournisseur, tel qu'il apparait dans
     * {@code leadflow.crm.providers.<id>} de la configuration.
     */
    String providerId();

    /**
     * Cree ou met a jour le tiers, le contact, l'opportunite et la tache de rappel dans
     * l'ERP cible.
     *
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP refuse ou est injoignable
     */
    CrmSyncResult sync(CrmLead lead);
}

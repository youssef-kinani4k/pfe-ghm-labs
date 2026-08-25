package com.leadflow.crm;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.util.List;

/**
 * Port de sortie vers un ERP/CRM. Une implementation par fournisseur supporte.
 *
 * <p>Les implementations sont des beans Spring ; {@link CrmConnectorRegistry} les collecte
 * automatiquement. Un adaptateur ne lit jamais notre base : tout ce dont il a besoin arrive
 * par ses arguments.
 */
public interface CrmConnector {

    /**
     * Identifiant stable du fournisseur, tel qu'il apparait dans
     * {@code leadflow.crm.providers.<id>} de la configuration.
     */
    String providerId();

    /**
     * Cree le compte, le contact et l'opportunite dans l'instance ERP designee par
     * {@code target}.
     *
     * <p>{@code previous} porte ce qui a deja ete cree lors des tentatives precedentes :
     * toute reference non nulle dispense de recreer l'objet correspondant. En cas d'echec
     * partiel, l'implementation leve une {@link com.leadflow.crm.model.CrmSyncException}
     * enrichie de ce qu'elle a obtenu avant de tomber.
     *
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP refuse ou est injoignable
     */
    CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous);

    /**
     * Traduit un commercial en identifiant utilisateur dans l'ERP cible.
     *
     * @return l'identifiant, ou {@code null} si l'ERP ne connait pas ce commercial
     * @throws com.leadflow.crm.model.CrmSyncException si l'ERP est injoignable
     */
    String resolveAssignee(CrmAssignee assignee, CrmTarget target);

    /**
     * Les reglages que ce fournisseur attend dans {@code client.crm_config}.
     *
     * <p>Declares ici et non recopies dans l'ecran d'administration : sans cela, ajouter un
     * ERP demanderait de modifier aussi {@code tenant/} et le formulaire Angular, alors que
     * le projet promet trois gestes et aucun {@code switch} sur le nom du fournisseur.
     */
    List<CrmSettingSpec> reglagesAttendus();

    /**
     * Verifie que la cible repond et accepte les identifiants, sans rien creer.
     *
     * <p>Obligatoire, sans implementation par defaut : un {@code default} rendant « non
     * verifiable » laisserait un futur adaptateur degrader silencieusement une promesse
     * faite a l'ecran de creation.
     */
    CrmCheck verifieAcces(CrmTarget cible);
}

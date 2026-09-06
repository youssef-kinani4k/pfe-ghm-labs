package com.leadflow.crm;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadReference;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Pousse un lead vers l'instance ERP de son client.
 *
 * <p>Porte tout ce qui est propre a LeadFlow — resolution de la cible, etat anterieur,
 * reference du commercial, trace — pour que les adaptateurs n'en portent rien. Ne choisit
 * aucun commercial (F4), ne consomme aucune file (F3), ne reessaie pas : la reprise sur
 * echec appartient a la file, 3 tentatives puis DLQ.
 *
 * <p>Volontairement non transactionnel : la trace s'ecrit via {@link CrmSyncTraceWriter}, en
 * transaction propre, pour survivre a la remontee de l'exception.
 */
@Service
public class CrmSyncService {

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final SalesRepRepository salesRepRepository;
    private final CrmSyncAttemptRepository attemptRepository;
    private final CrmConnectorRegistry registry;
    private final CrmSyncTraceWriter trace;

    public CrmSyncService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            SalesRepRepository salesRepRepository,
            CrmSyncAttemptRepository attemptRepository,
            CrmConnectorRegistry registry,
            CrmSyncTraceWriter trace) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
        this.salesRepRepository = salesRepRepository;
        this.attemptRepository = attemptRepository;
        this.registry = registry;
        this.trace = trace;
    }

    public CrmSyncResult synchronise(UUID leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead inconnu : " + leadId));
        Client client = clientRepository.findById(lead.getClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + leadId + " reference un client inexistant"));

        CrmConnector connector = registry.forProvider(client.getCrmProviderId());
        CrmTarget cible = new CrmTarget(client.getCrmProviderId(), client.getCrmConfig());
        CrmSyncState anterieur = etatAnterieurPour(leadId, client.getCrmProviderId());

        try {
            // Dans le try : resolveAssignee appelle l'ERP, et une instance injoignable doit
            // laisser une ligne FAILED exploitable plutot que de disparaitre sans trace.
            String assigneeRef = referenceDuCommercial(lead, connector, cible);
            CrmSyncResult resultat = connector.sync(versPivot(lead, assigneeRef), cible, anterieur);
            trace.succes(leadId, resultat);
            return resultat;
        } catch (CrmSyncException echec) {
            trace.echec(leadId, client.getCrmProviderId(),
                    fusionne(anterieur, echec.partialState()), echec.getMessage());
            throw echec;
        } catch (RuntimeException echec) {
            // Tout ce qui n'est pas une CrmSyncException doit laisser la meme trace. Un
            // adaptateur n'enveloppe que ce qu'il a prevu : Dolibarr rend un en-tete
            // WWW-Authenticate vide sur 401, et le parseur du JDK leve alors une
            // IllegalArgumentException qui traverse le client sans etre enveloppee. Sans ce
            // rattrapage, l'echec ERP le plus banal — une cle d'API fausse — partirait en
            // DLQ sans aucune ligne dans crm_sync_attempt, alors que cette table est la
            // source de verite du diagnostic et de l'etat anterieur au rejeu.
            trace.echec(leadId, client.getCrmProviderId(), anterieur,
                    echec.getClass().getSimpleName() + " : " + echec.getMessage());
            throw new CrmSyncException(client.getCrmProviderId(), echec.getMessage(), echec);
        }
    }

    /**
     * Prend, champ par champ, la valeur non nulle la plus recente. On ne peut pas se
     * contenter de la derniere ligne : une tentative echouee tot n'a que le compte, alors
     * qu'une tentative plus ancienne avait deja obtenu le contact.
     *
     * <p>Package-private et non privee depuis F15 : {@link CrmReassignService} reconstruit le
     * meme etat, et le dupliquer ferait diverger les deux lectures a la premiere evolution.
     */
    CrmSyncState etatAnterieurPour(UUID leadId, String providerId) {
        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, providerId);
        String compte = null;
        String contact = null;
        String opportunite = null;
        String responsable = null;
        for (CrmSyncAttempt tentative : tentatives) {
            compte = compte != null ? compte : tentative.getAccountRef();
            contact = contact != null ? contact : tentative.getContactRef();
            opportunite = opportunite != null ? opportunite : tentative.getOpportunityRef();
            responsable = responsable != null ? responsable : tentative.getAssigneeRef();
        }
        return new CrmSyncState(compte, contact, opportunite, responsable);
    }

    /** L'etat partiel de l'echec prime, mais ne doit rien perdre de ce qu'on savait deja. */
    private CrmSyncState fusionne(CrmSyncState anterieur, CrmSyncState partiel) {
        return new CrmSyncState(
                partiel.accountRef() != null ? partiel.accountRef() : anterieur.accountRef(),
                partiel.contactRef() != null ? partiel.contactRef() : anterieur.contactRef(),
                partiel.opportunityRef() != null
                        ? partiel.opportunityRef()
                        : anterieur.opportunityRef(),
                partiel.assigneeRef() != null ? partiel.assigneeRef() : anterieur.assigneeRef());
    }

    /** Resolu une seule fois par commercial et par instance : le resultat est memorise. */
    private String referenceDuCommercial(Lead lead, CrmConnector connector, CrmTarget cible) {
        if (lead.getAssignedSalesRepId() == null) {
            return null;
        }
        SalesRep commercial = salesRepRepository.findById(lead.getAssignedSalesRepId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + lead.getId() + " reference un commercial inexistant"));
        if (commercial.getCrmRef() != null) {
            return commercial.getCrmRef();
        }
        String reference = connector.resolveAssignee(
                new CrmAssignee(commercial.getFullName(), commercial.getEmail()), cible);
        if (reference != null) {
            commercial.setCrmRef(reference);
            salesRepRepository.save(commercial);
        }
        return reference;
    }

    private CrmLead versPivot(Lead lead, String assigneeRef) {
        return new CrmLead(
                LeadReference.pour(lead.getId()),
                lead.getCompanyName(),
                lead.getFirstName(),
                lead.getLastName(),
                lead.getEmail(),
                lead.getPhone(),
                lead.getMessage(),
                lead.getDetectedIntent(),
                lead.getScore(),
                lead.getCountryCode(),
                lead.getSector(),
                assigneeRef);
    }
}

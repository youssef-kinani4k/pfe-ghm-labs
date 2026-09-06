package com.leadflow.crm;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pousse vers l'ERP une correction de responsable decidee a la main.
 *
 * <p><b>C'est ici, et non dans {@code routing/}, que se decide s'il y a quelque chose a
 * corriger.</b> Le routage publie toujours : lui faire consulter {@code crm_sync_attempt}
 * avant de publier le couplerait a l'etat CRM et lui ferait porter une connaissance qui
 * appartient a cette etape.
 *
 * <p><b>Trois cas acquittent sans partir en DLQ</b>, avec une trace explicite — meme parti
 * que {@code IGNOREE} en notification et {@code DISCARDED} en qualification, parce
 * qu'aucune repetition ne les repare : lead jamais synchronise, commercial inconnu de l'ERP,
 * connecteur desactive. Seul un echec technique merite les trois tentatives puis la DLQ.
 *
 * <p>Volontairement non transactionnel, comme {@link CrmSyncService} : la trace porte la
 * sienne, en {@code REQUIRES_NEW}, pour survivre a la remontee de l'exception.
 */
@Service
public class CrmReassignService {

    private static final Logger log = LoggerFactory.getLogger(CrmReassignService.class);

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final SalesRepRepository salesRepRepository;
    private final CrmConnectorRegistry registry;
    private final CrmSyncService syncService;
    private final CrmSyncTraceWriter trace;

    public CrmReassignService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            SalesRepRepository salesRepRepository,
            CrmConnectorRegistry registry,
            CrmSyncService syncService,
            CrmSyncTraceWriter trace) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
        this.salesRepRepository = salesRepRepository;
        this.registry = registry;
        this.syncService = syncService;
        this.trace = trace;
    }

    public void propage(UUID leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead inconnu : " + leadId));
        Client client = clientRepository.findById(lead.getClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + leadId + " reference un client inexistant"));
        String providerId = client.getCrmProviderId();

        // La liste plutot que le rattrapage d'exception : forProvider leve une
        // IllegalArgumentException sur un connecteur desactive, et une exception non
        // attrapee ferait partir en DLQ un cas qui doit acquitter.
        if (providerId == null || !registry.availableProviders().contains(providerId)) {
            abandonne(leadId, providerId, "Connecteur indisponible pour cette boutique");
            return;
        }

        CrmSyncState anterieur = syncService.etatAnterieurPour(leadId, providerId);
        if (anterieur.opportunityRef() == null) {
            abandonne(leadId, providerId, "Lead jamais synchronise : rien a corriger chez l ERP");
            return;
        }

        CrmConnector connecteur = registry.forProvider(providerId);
        CrmTarget cible = new CrmTarget(providerId, client.getCrmConfig());

        try {
            String assigneeRef = referenceDuCommercial(lead, connecteur, cible);
            if (assigneeRef == null) {
                abandonne(leadId, providerId, "Commercial inconnu de l ERP : rien a poser");
                return;
            }
            connecteur.reaffecte(anterieur, assigneeRef, cible);
            trace.reaffectationReussie(leadId, providerId, assigneeRef);
            log.info("Responsable du lead {} corrige chez {}", leadId, providerId);
        } catch (CrmSyncException echec) {
            trace.reaffectationEchouee(leadId, providerId, echec.getMessage());
            throw echec;
        } catch (RuntimeException echec) {
            // Meme rattrapage qu'en synchronisation : un adaptateur n'enveloppe que ce qu'il
            // a prevu, et l'echec le plus banal ne doit pas partir en DLQ sans trace.
            trace.reaffectationEchouee(leadId, providerId,
                    echec.getClass().getSimpleName() + " : " + echec.getMessage());
            throw new CrmSyncException(providerId, echec.getMessage(), echec);
        }
    }

    /**
     * Acquitte en laissant une trace lisible. {@code SUCCESS} et non {@code FAILED} : rien
     * n'a echoue, il n'y avait rien a faire — et une ligne rouge apprendrait a l'operateur a
     * ignorer les rouges suivantes.
     */
    private void abandonne(UUID leadId, String providerId, String raison) {
        log.info("Propagation du lead {} sans effet : {}", leadId, raison);
        trace.reaffectationSansEffet(leadId, providerId == null ? "inconnu" : providerId, raison);
    }

    /** Resolu une seule fois par commercial et par instance, comme en synchronisation. */
    private String referenceDuCommercial(Lead lead, CrmConnector connecteur, CrmTarget cible) {
        if (lead.getAssignedSalesRepId() == null) {
            return null;
        }
        SalesRep commercial = salesRepRepository.findById(lead.getAssignedSalesRepId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + lead.getId() + " reference un commercial inexistant"));
        if (commercial.getCrmRef() != null) {
            return commercial.getCrmRef();
        }
        String reference = connecteur.resolveAssignee(
                new CrmAssignee(commercial.getFullName(), commercial.getEmail()), cible);
        if (reference != null) {
            commercial.setCrmRef(reference);
            salesRepRepository.save(commercial);
        }
        return reference;
    }
}

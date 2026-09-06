package com.leadflow.crm;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
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
 * <p><b>Quatre cas acquittent sans partir en DLQ</b>, avec une trace explicite — meme parti
 * que {@code IGNOREE} en notification et {@code DISCARDED} en qualification, parce
 * qu'aucune repetition ne les repare : connecteur desactive, lead jamais synchronise, lead
 * sans commercial, commercial inconnu de l'ERP. Seul un echec technique merite les trois
 * tentatives puis la DLQ.
 *
 * <p>Chacune de ces sorties est <b>hors du bloc {@code try}</b>, et ce n'est pas cosmetique :
 * a l'interieur, un echec d'ecriture de la trace « sans effet » serait rattrape, retrace en
 * {@code FAILED} puis enveloppe — donc DLQ pour un cas qui doit acquitter.
 *
 * <p>Volontairement non transactionnel, comme {@link CrmSyncService} : la trace porte la
 * sienne, en {@code REQUIRES_NEW}, pour survivre a la remontee de l'exception.
 */
@Service
public class CrmReassignService {

    private static final Logger log = LoggerFactory.getLogger(CrmReassignService.class);

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final CrmConnectorRegistry registry;
    private final CrmSyncService syncService;
    private final CrmSyncTraceWriter trace;

    public CrmReassignService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            CrmConnectorRegistry registry,
            CrmSyncService syncService,
            CrmSyncTraceWriter trace) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
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

        // Distingue de « commercial inconnu de l ERP » plus bas : ici il n'y a personne a
        // poser, la ne repond pas. Confondre les deux ferait chercher a l'operateur un
        // utilisateur ERP manquant qui n'a jamais ete demande.
        if (lead.getAssignedSalesRepId() == null) {
            abandonne(leadId, providerId, "Lead sans commercial : rien a poser chez l ERP");
            return;
        }

        CrmConnector connecteur = registry.forProvider(providerId);
        CrmTarget cible = new CrmTarget(providerId, client.getCrmConfig());

        String assigneeRef;
        try {
            // Dans le try : resolveAssignee appelle l'ERP, et une instance injoignable doit
            // laisser une trace FAILED plutot que de disparaitre.
            assigneeRef = syncService.referenceDuCommercial(lead, connecteur, cible);
        } catch (RuntimeException echec) {
            throw traceEtRelaie(leadId, providerId, echec);
        }
        if (assigneeRef == null) {
            abandonne(leadId, providerId, "Commercial inconnu de l ERP : rien a poser");
            return;
        }

        try {
            connecteur.reaffecte(anterieur, assigneeRef, cible);
        } catch (RuntimeException echec) {
            throw traceEtRelaie(leadId, providerId, echec);
        }
        trace.reaffectationReussie(leadId, providerId, assigneeRef);
        log.info("Responsable du lead {} corrige chez {}", leadId, providerId);
    }

    /**
     * Trace l'echec <b>avant</b> de rendre l'exception a relancer, en {@code REQUIRES_NEW} :
     * sans cela la ligne disparaitrait avec le rollback du consommateur, au moment ou elle
     * sert le plus.
     *
     * <p>Rend l'exception au lieu de la lever pour que l'appelant ecrive {@code throw
     * traceEtRelaie(...)} — le compilateur voit alors que le flot s'arrete la.
     */
    private RuntimeException traceEtRelaie(UUID leadId, String providerId, RuntimeException echec) {
        if (echec instanceof CrmSyncException) {
            trace.reaffectationEchouee(leadId, providerId, echec.getMessage());
            return echec;
        }
        // Meme rattrapage qu'en synchronisation : un adaptateur n'enveloppe que ce qu'il a
        // prevu, et l'echec le plus banal ne doit pas partir en DLQ sans trace.
        trace.reaffectationEchouee(leadId, providerId,
                echec.getClass().getSimpleName() + " : " + echec.getMessage());
        return new CrmSyncException(providerId, echec.getMessage(), echec);
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
}

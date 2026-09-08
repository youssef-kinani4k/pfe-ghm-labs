package com.leadflow.routing;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reattribution manuelle d'un lead deja attribue.
 *
 * <p><b>Aucun message de routage n'est publie.</b> Le tour de role n'est pas idempotent —
 * rejouer une attribution decale la rotation — et republier sur {@code lead.routed}
 * renverrait vers l'ERP un lead deja synchronise en entier. Depuis F15, une cle distincte
 * part en revanche : {@code lead.reassigned} ne transporte qu'un changement de responsable,
 * et poser un responsable est idempotent.
 *
 * <p><b>Non transactionnel</b>, comme {@link LeadRoutingService} : l'ecriture porte sa
 * propre transaction ({@link RoutedLeadWriter}), et le journal la sienne. C'est ce qui
 * permet de journaliser <b>apres</b> le commit — l'ordre inverse laisserait une ligne
 * affirmant un changement qui n'a pas eu lieu.
 *
 * <p>Le risque assume est symetrique et moindre : une panne entre les deux etapes donne un
 * lead reattribue sans trace, que la timeline montre comme une attribution sans action.
 */
@Service
public class ReattributionService {

    private static final Logger log = LoggerFactory.getLogger(ReattributionService.class);

    private final LeadRepository leads;
    private final SalesRepRepository commerciaux;
    private final RoutedLeadWriter writer;
    private final LeadActionJournal journal;
    private final LeadReassignedPublisher publieur;

    public ReattributionService(
            LeadRepository leads,
            SalesRepRepository commerciaux,
            RoutedLeadWriter writer,
            LeadActionJournal journal,
            LeadReassignedPublisher publieur) {
        this.leads = leads;
        this.commerciaux = commerciaux;
        this.writer = writer;
        this.journal = journal;
        this.publieur = publieur;
    }

    public Lead reattribue(UUID leadId, UUID salesRepId, String motif, String operateur) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        UUID ancien = lead.getAssignedSalesRepId();
        if (ancien == null) {
            throw new ReattributionImpossibleException(
                    "Ce lead n a pas encore de commercial : il n y a rien a reattribuer");
        }
        if (ancien.equals(salesRepId)) {
            throw new ReattributionImpossibleException(
                    "Ce commercial est deja celui du lead");
        }

        SalesRep cible = commerciaux.findById(salesRepId).orElseThrow(
                () -> new ReattributionImpossibleException(
                        "Commercial inconnu : " + salesRepId));
        if (!cible.isActive()) {
            throw new ReattributionImpossibleException(
                    "Ce commercial est desactive : le reactiver d abord");
        }
        // Le controle qui n'est pas theorique : l'identifiant vient du client, et rien
        // d'autre n'empecherait d'attribuer le lead d'une boutique au commercial d'une autre.
        if (!cible.getClient().getId().equals(lead.getClientId())) {
            throw new ReattributionImpossibleException(
                    "Ce commercial appartient a une autre boutique");
        }

        Lead reattribue = writer.reattribue(leadId, salesRepId);

        LeadAction action = new LeadAction();
        action.setLeadId(leadId);
        action.setAction(LeadActionType.REATTRIBUTION);
        action.setActor(operateur);
        action.setReason(motif);
        action.setPreviousSalesRepId(ancien);
        action.setNewSalesRepId(salesRepId);
        action.setOutcome(LeadActionOutcome.SUCCES);
        journal.enregistre(action);

        // Apres le journal, jamais avant : l'ordre inverse annoncerait a l'ERP un changement
        // dont la trace peut encore manquer.
        publieur.publie(leadId, lead.getClientId(), ancien, salesRepId);

        log.info("Lead {} reattribue de {} a {} par {}", leadId, ancien, salesRepId, operateur);
        return reattribue;
    }
}

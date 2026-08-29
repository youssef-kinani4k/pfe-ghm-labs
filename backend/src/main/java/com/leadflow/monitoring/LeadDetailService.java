package com.leadflow.monitoring;

import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.monitoring.dto.LeadDetail;
import com.leadflow.monitoring.dto.RawEventView;
import com.leadflow.monitoring.dto.SalesRepView;
import com.leadflow.monitoring.dto.SyncAttemptView;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assemble le detail d'un lead a partir des traces des trois etapes qui l'ont touche.
 *
 * <p>Tout ce qui manque est rendu nul plutot que de faire echouer l'appel : un lead qui
 * vient d'etre qualifie n'a ni commercial ni tentative, et c'est un etat normal que l'ecran
 * doit savoir montrer. Seul un lead inconnu leve.
 */
@Service
public class LeadDetailService {

    private final LeadQueryRepository leads;
    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;
    private final CrmSyncAttemptRepository tentatives;
    private final RawLeadEventRepository evenements;

    public LeadDetailService(
            LeadQueryRepository leads,
            ClientRepository clients,
            SalesRepRepository commerciaux,
            CrmSyncAttemptRepository tentatives,
            RawLeadEventRepository evenements) {
        this.leads = leads;
        this.clients = clients;
        this.commerciaux = commerciaux;
        this.tentatives = tentatives;
        this.evenements = evenements;
    }

    @Transactional(readOnly = true)
    public LeadDetail detail(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        // La boutique est gardee entiere et non reduite a son nom : le drapeau `chaud` a
        // besoin de son bareme, et la relire une seconde fois pour cela serait une requete
        // de plus pour rien.
        Client boutique = clients.findById(lead.getClientId()).orElse(null);
        String nomDuClient = boutique == null ? null : boutique.getName();
        boolean chaud = boutique != null
                && lead.getScore()
                        >= ScoringConfig.depuis(boutique.getScoringConfig()).seuilChaud();

        SalesRepView commercial = lead.getAssignedSalesRepId() == null
                ? null
                : commerciaux.findById(lead.getAssignedSalesRepId())
                        .map(rep -> new SalesRepView(
                                rep.getId(), rep.getFullName(), rep.getEmail(),
                                rep.getSector(), rep.getZone(), rep.getCrmRef()))
                        .orElse(null);

        List<SyncAttemptView> historique =
                tentatives.findByLeadIdOrderByAttemptedAtDesc(leadId).stream()
                        .map(this::vue)
                        .toList();

        RawEventView brut = evenements.findById(lead.getRawEventId())
                .map(this::vue)
                .orElse(null);

        return new LeadDetail(
                lead.getId(), lead.getCreatedAt(), lead.getUpdatedAt(), lead.getClientId(),
                nomDuClient, lead.getCompanyName(), lead.getFirstName(), lead.getLastName(),
                lead.getEmail(), lead.getPhone(), lead.getMessage(), lead.getDetectedIntent(),
                lead.getIntentSource(), lead.getScore(), lead.getStatus(),
                lead.getCountryCode(), lead.getSector(), commercial, historique, brut, chaud);
    }

    private SyncAttemptView vue(CrmSyncAttempt tentative) {
        return new SyncAttemptView(
                tentative.getId(), tentative.getProviderId(), tentative.getStatus(),
                tentative.getAccountRef(), tentative.getContactRef(),
                tentative.getOpportunityRef(), tentative.getTaskRef(),
                tentative.getErrorMessage(), tentative.getAttemptedAt());
    }

    private RawEventView vue(RawLeadEvent evenement) {
        return new RawEventView(
                evenement.getId(), evenement.getSource(), evenement.getReceivedAt(),
                evenement.getStatus(), evenement.getFailureReason(), evenement.getPayload());
    }
}

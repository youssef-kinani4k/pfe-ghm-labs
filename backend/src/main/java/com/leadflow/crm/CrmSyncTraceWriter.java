package com.leadflow.crm;

import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit la trace d'une tentative dans une transaction qui lui est propre.
 *
 * <p>Bean distinct de {@link CrmSyncService} a dessein : Spring ne proxie pas un appel de
 * methode interne, et {@code REQUIRES_NEW} est indispensable ici. Quand l'echec remonte a un
 * appelant transactionnel — le consommateur RabbitMQ de F3 — son rollback emporterait sinon
 * la ligne {@code FAILED}, donc le diagnostic et les references deja obtenues.
 */
@Component
public class CrmSyncTraceWriter {

    private final CrmSyncAttemptRepository attemptRepository;
    private final LeadRepository leadRepository;

    public CrmSyncTraceWriter(
            CrmSyncAttemptRepository attemptRepository, LeadRepository leadRepository) {
        this.attemptRepository = attemptRepository;
        this.leadRepository = leadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succes(UUID leadId, CrmSyncResult resultat) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(resultat.providerId());
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAccountRef(resultat.accountRef());
        tentative.setContactRef(resultat.contactRef());
        tentative.setOpportunityRef(resultat.opportunityRef());
        tentative.setTaskRef(resultat.taskRef());
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
        changeStatut(leadId, LeadStatus.SYNCED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void echec(UUID leadId, String providerId, CrmSyncState partiel, String message) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.FAILED);
        tentative.setAccountRef(partiel.accountRef());
        tentative.setContactRef(partiel.contactRef());
        tentative.setOpportunityRef(partiel.opportunityRef());
        tentative.setErrorMessage(message);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
        changeStatut(leadId, LeadStatus.FAILED);
    }

    private void changeStatut(UUID leadId, LeadStatus statut) {
        Lead lead = leadRepository.findById(leadId).orElseThrow();
        lead.setStatus(statut);
        leadRepository.save(lead);
    }
}

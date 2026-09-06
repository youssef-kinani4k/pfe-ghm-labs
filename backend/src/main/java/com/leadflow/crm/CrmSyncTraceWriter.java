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
        tentative.setNature(CrmSyncAttemptNature.SYNCHRONISATION);
        tentative.setAccountRef(resultat.accountRef());
        tentative.setContactRef(resultat.contactRef());
        tentative.setOpportunityRef(resultat.opportunityRef());
        tentative.setAssigneeRef(resultat.assigneeRef());
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
        tentative.setNature(CrmSyncAttemptNature.SYNCHRONISATION);
        tentative.setAccountRef(partiel.accountRef());
        tentative.setContactRef(partiel.contactRef());
        tentative.setOpportunityRef(partiel.opportunityRef());
        tentative.setAssigneeRef(partiel.assigneeRef());
        tentative.setErrorMessage(message);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
        changeStatut(leadId, LeadStatus.FAILED);
    }

    /**
     * Trace d'une correction de responsable reussie.
     *
     * <p><b>Ne touche pas le statut du lead</b>, contrairement a {@link #succes}. Un lead
     * reaffecte etait deja {@code SYNCED} et le reste ; le faire repasser par un changement
     * de statut n'apprendrait rien et ferait mentir {@code updated_at}.
     *
     * <p>La ligne ne porte que {@code assignee_ref}, et c'est suffisant : {@code
     * etatAnterieurPour} prend la valeur non nulle la plus recente champ par champ, donc les
     * trois autres references restent celles de la synchronisation d'origine.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reaffectationReussie(UUID leadId, String providerId, String assigneeRef) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setNature(CrmSyncAttemptNature.REAFFECTATION);
        tentative.setAssigneeRef(assigneeRef);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
    }

    /**
     * Trace d'une correction de responsable en echec, ecrite <b>avant</b> que l'exception ne
     * parte : en {@code REQUIRES_NEW}, elle survit au rollback du consommateur, comme la
     * ligne {@code FAILED} d'une synchronisation.
     *
     * <p>Ne touche pas davantage le statut : faire retomber en {@code FAILED} un lead
     * correctement synchronise parce que la correction de son responsable n'est pas passee
     * serait une regression visible sur toutes les listes du dashboard.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reaffectationEchouee(UUID leadId, String providerId, String message) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(CrmSyncAttemptStatus.FAILED);
        tentative.setNature(CrmSyncAttemptNature.REAFFECTATION);
        tentative.setErrorMessage(message);
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.save(tentative);
    }

    private void changeStatut(UUID leadId, LeadStatus statut) {
        Lead lead = leadRepository.findById(leadId).orElseThrow();
        lead.setStatus(statut);
        leadRepository.save(lead);
    }
}

package com.leadflow.crm;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fait passer le lead a {@code SYNCED} apres une synchronisation reussie.
 *
 * <p>Classe a part plutot qu'une ligne dans {@code CrmSyncService} : celui-ci ne connait pas
 * le cycle de vie du lead, il synchronise et trace. Lui confier le statut ferait dependre
 * l'adaptation vers l'ERP d'une notion qui appartient au pipeline.
 *
 * <p>Transaction propre, pour la meme raison que les autres ecrivains du projet : l'appelant
 * n'est pas transactionnel.
 */
@Component
public class SyncedLeadWriter {

    private final LeadRepository leadRepository;

    public SyncedLeadWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void marqueSynchronise(UUID leadId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu apres synchronisation : " + leadId));
        lead.setStatus(LeadStatus.SYNCED);
        leadRepository.saveAndFlush(lead);
    }
}

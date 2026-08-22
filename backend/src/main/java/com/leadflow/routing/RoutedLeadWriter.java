package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit l'attribution dans sa <b>propre</b> transaction.
 *
 * <p>Meme raison qu'en F3 pour {@code LeadWriter} : quand cette methode rend la main, la
 * ligne est <b>commitee</b>. C'est ce qui autorise l'orchestrateur a publier vers le broker
 * par un appel direct — un message parti plus tot designerait un lead que le consommateur
 * suivant lirait encore {@code QUALIFIED}, sans commercial.
 *
 * <p>Bean distinct et non methode privee : Spring ne proxie pas l'auto-invocation, la
 * propagation serait silencieusement ignoree.
 */
@Component
public class RoutedLeadWriter {

    private final LeadRepository leadRepository;

    public RoutedLeadWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lead attribue(UUID leadId, UUID salesRepId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu en cours d'attribution : " + leadId));
        lead.setAssignedSalesRepId(salesRepId);
        lead.setStatus(LeadStatus.ROUTED);
        return leadRepository.saveAndFlush(lead);
    }
}

package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
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
 *
 * <p><b>Ce que cette transaction ne protege pas.</b> Elle donne l'atomicite de l'ecriture,
 * pas l'isolation vis-a-vis d'un lecteur concurrent : {@code BaseEntity} ne porte pas de
 * {@code @Version}, et deux livraisons simultanees du meme {@code lead.qualified} liraient
 * toutes deux un lead sans commercial, l'attribueraient, puis publieraient deux messages.
 * Le deploiement est mono-instance et {@code concurrentConsumers} vaut 1, donc le cas ne se
 * presente pas aujourd'hui ; le durcir demanderait une ecriture conditionnelle
 * ({@code where assigned_sales_rep_id is null}) relue a zero ligne modifiee, ce qui ferait
 * de l'attribution un compare-and-set sans migration ni verrou.
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
        // Dans la meme transaction que les deux lignes ci-dessus : les trois sont un seul
        // fait, et un routed_at sans commercial serait un etat incoherent.
        lead.setRoutedAt(Instant.now());
        return leadRepository.saveAndFlush(lead);
    }

    /**
     * Reattribution manuelle : elle pose le commercial, et <b>rien d'autre</b>.
     *
     * <p>Le statut appartient au pipeline — un lead {@code SYNCED} reste {@code SYNCED},
     * puisqu'il est bien dans l'ERP. Et {@code routedAt} date l'attribution automatique, un
     * fait qui a eu lieu : la reattribution est un fait distinct, date par sa ligne de
     * {@code lead_action}. Reecrire l'un ou l'autre ferait mentir la chronologie.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lead reattribue(UUID leadId, UUID salesRepId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new IllegalStateException("Lead disparu en cours de reattribution : " + leadId));
        lead.setAssignedSalesRepId(salesRepId);
        return leadRepository.saveAndFlush(lead);
    }
}

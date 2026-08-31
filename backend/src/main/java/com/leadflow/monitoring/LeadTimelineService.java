package com.leadflow.monitoring;

import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.deadletter.DeadLetter;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.dto.TimelineEntry;
import com.leadflow.monitoring.dto.TimelineEventType;
import com.leadflow.monitoring.dto.TimelineOutcome;
import com.leadflow.qualification.Lead;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derive la chronologie d'un lead depuis les traces des etapes qui l'ont touche.
 *
 * <p><b>Ce service n'ecrit rien.</b> C'est la condition qui rend {@code monitoring/} sur :
 * le package lit les tables des autres etapes et n'ecrit que {@code dead_letter}.
 */
@Service
public class LeadTimelineService {

    private final LeadQueryRepository leads;
    private final RawLeadEventRepository evenements;
    private final CrmSyncAttemptRepository tentatives;
    private final DeadLetterRepository morts;

    public LeadTimelineService(
            LeadQueryRepository leads,
            RawLeadEventRepository evenements,
            CrmSyncAttemptRepository tentatives,
            DeadLetterRepository morts) {
        this.leads = leads;
        this.evenements = evenements;
        this.tentatives = tentatives;
        this.morts = morts;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        List<TimelineEntry> entrees = new ArrayList<>();
        evenements.findById(lead.getRawEventId()).ifPresent(e -> entrees.add(capture(e)));
        entrees.add(qualification(lead));
        if (lead.getAssignedSalesRepId() != null) {
            entrees.add(attribution(lead));
        }
        tentatives.findByLeadIdOrderByAttemptedAtDesc(leadId)
                .forEach(tentative -> entrees.add(synchronisation(tentative)));

        morts.findByLeadIdOrderByDeadAtAsc(leadId).forEach(mort -> {
            entrees.add(mort(mort));
            if (mort.getReplayedAt() != null) {
                entrees.add(rejeu(mort));
            }
        });

        return ordonne(entrees);
    }

    /**
     * Les entrees datees se trient chronologiquement ; une entree sans date reste a la
     * position que son type occupe dans le pipeline.
     *
     * <p>Un tri qui rejetterait les nuls en tete ou en queue — le comportement par defaut
     * de la plupart des comparateurs — mentirait sur toute la chronologie anterieure a V7.
     * L'ordre des etapes est connu meme quand leur date ne l'est pas.
     */
    private List<TimelineEntry> ordonne(List<TimelineEntry> entrees) {
        List<TimelineEntry> datees = new ArrayList<>(
                entrees.stream().filter(e -> e.at() != null).toList());
        datees.sort(Comparator.comparing(TimelineEntry::at));

        List<TimelineEntry> resultat = new ArrayList<>(datees);
        for (TimelineEntry sansDate : entrees.stream().filter(e -> e.at() == null).toList()) {
            resultat.add(positionDe(resultat, sansDate.type()), sansDate);
        }
        return List.copyOf(resultat);
    }

    /**
     * Position d'un type non date : juste apres la derniere entree dont le type le precede
     * dans le pipeline. L'ordre de declaration de {@link TimelineEventType} est l'ordre du
     * pipeline, donc {@code ordinal()} suffit a le lire.
     */
    private int positionDe(List<TimelineEntry> deja, TimelineEventType type) {
        int position = 0;
        for (int i = 0; i < deja.size(); i++) {
            if (deja.get(i).type().ordinal() <= type.ordinal()) {
                position = i + 1;
            }
        }
        return position;
    }

    private TimelineEntry capture(RawLeadEvent evenement) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("source", evenement.getSource());
        details.put("statut", String.valueOf(evenement.getStatus()));
        if (evenement.getFailureReason() != null) {
            details.put("erreur", evenement.getFailureReason());
        }
        return new TimelineEntry(
                TimelineEventType.CAPTURE,
                evenement.getReceivedAt(),
                evenement.getFailureReason() == null
                        ? TimelineOutcome.NEUTRE
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }

    private TimelineEntry qualification(Lead lead) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("score", String.valueOf(lead.getScore()));
        if (lead.getDetectedIntent() != null) {
            details.put("intention", lead.getDetectedIntent());
        }
        if (lead.getIntentSource() != null) {
            details.put("sourceIntention", String.valueOf(lead.getIntentSource()));
        }
        return new TimelineEntry(
                TimelineEventType.QUALIFICATION,
                lead.getCreatedAt(),
                TimelineOutcome.NEUTRE,
                Map.copyOf(details));
    }

    private TimelineEntry attribution(Lead lead) {
        return new TimelineEntry(
                TimelineEventType.ATTRIBUTION,
                lead.getRoutedAt(),
                TimelineOutcome.NEUTRE,
                Map.of("commercialId", String.valueOf(lead.getAssignedSalesRepId())));
    }

    private TimelineEntry mort(DeadLetter mort) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("file", mort.getOriginQueue());
        if (mort.getFailureReason() != null) {
            details.put("erreur", mort.getFailureReason());
        }
        return new TimelineEntry(
                TimelineEventType.MORT, mort.getDeadAt(), TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }

    /**
     * Le rejeu n'existe que si la date est posee : un message mort et non rejoue n'a pas
     * d'entree de rejeu, meme si la colonne {@code replayed_by} porte une valeur.
     */
    private TimelineEntry rejeu(DeadLetter mort) {
        Map<String, String> details = new LinkedHashMap<>();
        if (mort.getReplayedBy() != null) {
            details.put("par", mort.getReplayedBy());
        }
        return new TimelineEntry(
                TimelineEventType.REJEU, mort.getReplayedAt(), TimelineOutcome.NEUTRE,
                Map.copyOf(details));
    }

    private TimelineEntry synchronisation(CrmSyncAttempt tentative) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("connecteur", tentative.getProviderId());
        details.put("statut", String.valueOf(tentative.getStatus()));
        if (tentative.getErrorMessage() != null) {
            details.put("erreur", tentative.getErrorMessage());
        }
        return new TimelineEntry(
                TimelineEventType.SYNC_ERP,
                tentative.getAttemptedAt(),
                tentative.getStatus() == CrmSyncAttemptStatus.SUCCESS
                        ? TimelineOutcome.SUCCES
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }
}

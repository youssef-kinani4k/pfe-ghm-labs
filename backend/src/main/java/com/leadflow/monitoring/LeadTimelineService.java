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
import com.leadflow.notification.NotificationAttempt;
import com.leadflow.notification.NotificationAttemptRepository;
import com.leadflow.notification.NotificationStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.routing.LeadAction;
import com.leadflow.routing.LeadActionOutcome;
import com.leadflow.routing.LeadActionRepository;
import com.leadflow.routing.LeadActionType;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final LeadActionRepository actions;
    private final NotificationAttemptRepository notifications;
    private final SalesRepRepository commerciaux;

    public LeadTimelineService(
            LeadQueryRepository leads,
            RawLeadEventRepository evenements,
            CrmSyncAttemptRepository tentatives,
            DeadLetterRepository morts,
            LeadActionRepository actions,
            NotificationAttemptRepository notifications,
            SalesRepRepository commerciaux) {
        this.leads = leads;
        this.evenements = evenements;
        this.tentatives = tentatives;
        this.morts = morts;
        this.actions = actions;
        this.notifications = notifications;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        List<TimelineEntry> entrees = new ArrayList<>();
        evenements.findById(lead.getRawEventId()).ifPresent(e -> entrees.add(capture(e)));
        entrees.add(qualification(lead));
        List<LeadAction> journal = actions.findByLeadIdOrderByCreatedAtAsc(leadId);
        Map<UUID, String> noms = nomsDesCommerciaux(lead, journal);

        if (lead.getAssignedSalesRepId() != null) {
            entrees.add(attribution(lead, journal, noms));
        }
        tentatives.findByLeadIdOrderByAttemptedAtDesc(leadId)
                .forEach(tentative -> entrees.add(synchronisation(tentative)));
        notifications.findByLeadIdOrderByAttemptedAtAsc(leadId)
                .forEach(tentative -> entrees.add(notification(tentative)));

        Set<UUID> mortsDejaJournalisees = journal.stream()
                .map(LeadAction::getDeadLetterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        morts.findByLeadIdOrderByDeadAtAsc(leadId).forEach(mort -> {
            entrees.add(mort(mort));
            // Le rejeu derive de dead_letter ne sert plus que l'historique anterieur a V8 :
            // des qu'une ligne de journal reference cette mort, c'est elle qui parle, et
            // elle en dit plus — le motif, et l'issue.
            if (mort.getReplayedAt() != null
                    && !mortsDejaJournalisees.contains(mort.getId())) {
                entrees.add(rejeu(mort));
            }
        });

        journal.forEach(action -> entrees.add(action(action, noms)));

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

    private TimelineEntry attribution(Lead lead, List<LeadAction> journal, Map<UUID, String> noms) {
        return new TimelineEntry(
                TimelineEventType.ATTRIBUTION,
                lead.getRoutedAt(),
                TimelineOutcome.NEUTRE,
                Map.of("commercial", nomDe(titulaireDOrigine(lead, journal), noms)));
    }

    /**
     * Le commercial de l'attribution d'origine, et non le titulaire courant.
     *
     * <p>{@code lead.assigned_sales_rep_id} ne retient que le dernier en date : le lire ici
     * ferait dire a la chronologie qu'un lead reattribue a toujours appartenu a son titulaire
     * actuel, et la ligne « Reattribution » juste en dessous la contredirait. L'origine se lit
     * donc du journal — c'est le {@code previousSalesRepId} de la <b>premiere</b>
     * reattribution, celle qui a deplace le lead hors de son premier titulaire.
     *
     * <p>La derivation est exacte, et non approchee : F10 a introduit ensemble la
     * reattribution et son journal, donc il n'existe aucune reattribution non journalisee, et
     * {@code ReattributionService} refuse en {@code 409} un lead sans commercial — la colonne
     * ne peut pas etre nulle sur une ligne {@code REATTRIBUTION}. A defaut de toute
     * reattribution, le cas de loin le plus frequent, le titulaire courant <i>est</i> celui
     * de l'origine.
     *
     * <p>Le journal arrive trie par date croissante, ce que {@code findByLeadIdOrderBy...}
     * garantit : {@code findFirst} y lit donc bien la plus ancienne, et non l'avant-derniere.
     */
    private UUID titulaireDOrigine(Lead lead, List<LeadAction> journal) {
        return journal.stream()
                .filter(action -> action.getAction() == LeadActionType.REATTRIBUTION)
                .map(LeadAction::getPreviousSalesRepId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(lead::getAssignedSalesRepId);
    }

    /**
     * Les noms des commerciaux cites par la chronologie, en une seule requete.
     *
     * <p>Le nom est un fait au meme titre que l'identifiant, et c'est celui que l'ecran d'une
     * agence sait lire : un UUID n'y apprend rien a personne. Les resoudre ici plutot que
     * dans le template evite au frontend de connaitre la boutique du lead — la chronologie
     * est chargee seule, sans le detail.
     */
    private Map<UUID, String> nomsDesCommerciaux(Lead lead, List<LeadAction> journal) {
        Set<UUID> identifiants = new HashSet<>();
        identifiants.add(lead.getAssignedSalesRepId());
        journal.forEach(action -> {
            identifiants.add(action.getPreviousSalesRepId());
            identifiants.add(action.getNewSalesRepId());
        });
        identifiants.remove(null);
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return commerciaux.findAllById(identifiants).stream()
                .collect(Collectors.toMap(SalesRep::getId, SalesRep::getFullName));
    }

    /**
     * Le nom, ou l'identifiant a defaut. {@code lead_action} ne porte pas de cle etrangere
     * vers {@code sales_rep} — pour qu'un commercial supprime n'efface pas l'histoire — donc
     * l'introuvable est un cas normal, et son identifiant reste la seule chose vraie qu'on
     * puisse montrer.
     */
    private String nomDe(UUID identifiant, Map<UUID, String> noms) {
        return noms.getOrDefault(identifiant, String.valueOf(identifiant));
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

    /**
     * Un geste humain. Les cles de {@code details} sont des faits, jamais des phrases : la
     * mise en francais appartient au template Angular.
     */
    private TimelineEntry action(LeadAction action, Map<UUID, String> noms) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("par", action.getActor());
        details.put("motif", action.getReason());
        if (action.getPreviousSalesRepId() != null) {
            details.put("ancienCommercial", nomDe(action.getPreviousSalesRepId(), noms));
        }
        if (action.getNewSalesRepId() != null) {
            details.put("nouveauCommercial", nomDe(action.getNewSalesRepId(), noms));
        }
        if (action.getDetail() != null) {
            details.put("erreur", action.getDetail());
        }
        return new TimelineEntry(
                typeDe(action.getAction()),
                action.getCreatedAt(),
                action.getOutcome() == LeadActionOutcome.SUCCES
                        ? TimelineOutcome.NEUTRE
                        : TimelineOutcome.ECHEC,
                Map.copyOf(details));
    }

    /**
     * Correspondance un pour un avec {@link TimelineEventType} : un ecart n'est pas un
     * rejeu, meme s'il suit la meme mort — l'un abandonne le message, l'autre le republie,
     * et confondre les deux a l'ecran dirait un succes la ou il y a un renoncement. Ecrit en
     * expression, sans branche par defaut, pour qu'une valeur ajoutee a
     * {@link LeadActionType} ne compile plus tant qu'elle n'a pas sa place ici.
     */
    private TimelineEventType typeDe(LeadActionType type) {
        return switch (type) {
            case REATTRIBUTION -> TimelineEventType.REATTRIBUTION;
            case REJEU -> TimelineEventType.REJEU;
            case ECART -> TimelineEventType.ECART;
        };
    }

    /**
     * Une tentative de notification, y compris ignoree. Une entree {@code IGNOREE} n'est pas
     * du bruit : elle explique un silence, et son absence ferait croire a une panne la ou il
     * n'y a eu qu'un lead sous le seuil de sa boutique. Le score et le seuil sont ceux figes
     * au moment de la decision, non relus, donc ils restent vrais apres un reglage du bareme.
     */
    private TimelineEntry notification(NotificationAttempt tentative) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("canal", tentative.getChannel());
        details.put("statut", String.valueOf(tentative.getStatus()));
        details.put("score", String.valueOf(tentative.getScore()));
        details.put("seuil", String.valueOf(tentative.getSeuil()));
        if (tentative.getRecipient() != null && !tentative.getRecipient().isBlank()) {
            details.put("destinataire", tentative.getRecipient());
        }
        if (tentative.getErrorMessage() != null) {
            details.put("erreur", tentative.getErrorMessage());
        }
        return new TimelineEntry(
                TimelineEventType.NOTIFICATION,
                tentative.getAttemptedAt(),
                tentative.getStatus() == NotificationStatus.ECHEC
                        ? TimelineOutcome.ECHEC
                        : TimelineOutcome.NEUTRE,
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

package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.deadletter.DeadLetter;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.monitoring.dto.TimelineEntry;
import com.leadflow.monitoring.dto.TimelineEventType;
import com.leadflow.monitoring.dto.TimelineOutcome;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Eprouve la derivation et surtout la regle d'ordre, qui est le seul point subtil du
 * service : une attribution sans date se place a sa position connue dans le pipeline, et
 * non en tete ou en queue comme le ferait un comparateur ordinaire.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadTimelineServiceTest {

    @Autowired private LeadTimelineService service;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private CrmSyncAttemptRepository tentatives;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;
    @Autowired private DeadLetterRepository morts;

    private UUID clientId;
    private UUID commercialId;
    /**
     * Le fixture est ancre sur l'instant courant, et non sur une date fixe : la date de
     * qualification est {@code lead.created_at}, posee par {@code @PrePersist} au moment de
     * l'insertion et donc impossible a choisir. Les autres dates se placent autour d'elle —
     * la capture une heure avant, l'attribution et la synchronisation quelques minutes
     * apres — pour que l'ordre attendu ne depende pas de la vitesse du test.
     */
    private final Instant base = Instant.now();

    /**
     * La suite complete partage une seule base Testcontainers : un lead laisse derriere lui
     * empeche {@code capture/} de vider {@code raw_lead_event}, dont il porte la cle
     * etrangere. La suppression suit donc l'ordre des references, comme dans les autres
     * classes de ce paquet.
     */
    @AfterEach
    void nettoyage() {
        morts.deleteAll();
        tentatives.deleteAll();
        leads.deleteAll();
        evenements.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @BeforeEach
    void boutique() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Est");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        Client boutique = clients.saveAndFlush(client);
        clientId = boutique.getId();

        // Un vrai commercial : lead.assigned_sales_rep_id porte une cle etrangere, donc un
        // identifiant invente ferait echouer l'insertion avant meme d'atteindre le service.
        SalesRep commercial = new SalesRep();
        commercial.setClient(boutique);
        commercial.setFullName("Karim Idrissi");
        commercial.setEmail("karim+" + UUID.randomUUID() + "@demo.test");
        commercial.setActive(true);
        commercialId = commerciaux.saveAndFlush(commercial).getId();
    }

    private UUID leadAvec(Instant recuA, Instant attribueA, LeadStatus statut) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(new HashMap<>(Map.of("email", "b@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(recuA);
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("b@exemple.fr");
        lead.setScore(80);
        lead.setDetectedIntent("DEVIS");
        lead.setStatus(statut);
        lead.setRoutedAt(attribueA);
        if (attribueA != null || statut != LeadStatus.QUALIFIED) {
            lead.setAssignedSalesRepId(commercialId);
        }
        return leads.saveAndFlush(lead).getId();
    }

    @Test
    void leadInconnuLeve() {
        UUID inconnu = UUID.randomUUID();
        assertThatThrownBy(() -> service.timeline(inconnu))
                .isInstanceOf(RessourceIntrouvableException.class);
    }

    @Test
    void leadJamaisSynchroniseNaAucuneEntreeErp() {
        UUID leadId = leadAvec(
                base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.MINUTES),
                LeadStatus.ROUTED);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION);
        assertThat(timeline).noneMatch(e -> e.outcome() == TimelineOutcome.ECHEC);
    }

    @Test
    void lesEntreesDateesSeTrientChronologiquement() {
        UUID leadId = leadAvec(
                base.minus(1, ChronoUnit.HOURS), base.plus(2, ChronoUnit.MINUTES),
                LeadStatus.SYNCED);

        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("dolibarr");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAttemptedAt(base.plus(5, ChronoUnit.MINUTES));
        tentatives.saveAndFlush(tentative);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION,
                TimelineEventType.SYNC_ERP);
        assertThat(timeline.get(3).outcome()).isEqualTo(TimelineOutcome.SUCCES);
    }

    @Test
    void uneAttributionSansDateSePlaceApresLaQualification() {
        // C'est tout l'historique anterieur a V7 : statut ROUTED, commercial pose,
        // routed_at nul.
        UUID leadId = leadAvec(base.minus(1, ChronoUnit.HOURS), null, LeadStatus.SYNCED);

        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("dolibarr");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAttemptedAt(base.plus(5, ChronoUnit.MINUTES));
        tentatives.saveAndFlush(tentative);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION,
                TimelineEventType.SYNC_ERP);
        assertThat(timeline.get(2).at()).isNull();
    }

    @Test
    void chaqueTentativeErpDonneUneEntree() {
        UUID leadId = leadAvec(
                base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.MINUTES),
                LeadStatus.ROUTED);

        for (int i = 1; i <= 3; i++) {
            CrmSyncAttempt tentative = new CrmSyncAttempt();
            tentative.setLeadId(leadId);
            tentative.setProviderId("dolibarr");
            tentative.setStatus(CrmSyncAttemptStatus.FAILED);
            tentative.setErrorMessage("Connection refused");
            tentative.setAttemptedAt(base.plus(2 + i, ChronoUnit.MINUTES));
            tentatives.saveAndFlush(tentative);
        }

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).filteredOn(e -> e.type() == TimelineEventType.SYNC_ERP)
                .hasSize(3)
                .allMatch(e -> e.outcome() == TimelineOutcome.ECHEC)
                .allMatch(e -> "Connection refused".equals(e.details().get("erreur")));
    }

    @Test
    void uneMortEtSonRejeuDonnentDeuxEntrees() {
        UUID leadId = leadAvec(
                base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.MINUTES),
                LeadStatus.ROUTED);

        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setClientId(clientId);
        mort.setLeadId(leadId);
        mort.setFailureReason("Connection refused");
        mort.setDeadAt(base.plus(10, ChronoUnit.MINUTES));
        mort.setStatus(DeadLetterStatus.REPLAYED);
        mort.setReplayedAt(base.plus(60, ChronoUnit.MINUTES));
        mort.setReplayedBy("admin");
        morts.saveAndFlush(mort);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).extracting(TimelineEntry::type).containsExactly(
                TimelineEventType.CAPTURE,
                TimelineEventType.QUALIFICATION,
                TimelineEventType.ATTRIBUTION,
                TimelineEventType.MORT,
                TimelineEventType.REJEU);
        assertThat(timeline.get(3).outcome()).isEqualTo(TimelineOutcome.ECHEC);
        assertThat(timeline.get(3).details().get("file")).isEqualTo("leadflow.leads.routed");
        assertThat(timeline.get(4).details().get("par")).isEqualTo("admin");
    }

    @Test
    void uneMortNonRejoueeNeDonneQuUneEntree() {
        UUID leadId = leadAvec(
                base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.MINUTES),
                LeadStatus.ROUTED);

        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.routed");
        mort.setRoutingKey("lead.routed");
        mort.setPayload("{}");
        mort.setClientId(clientId);
        mort.setLeadId(leadId);
        mort.setFailureReason("Connection refused");
        mort.setDeadAt(base.plus(10, ChronoUnit.MINUTES));
        mort.setStatus(DeadLetterStatus.PENDING);
        morts.saveAndFlush(mort);

        List<TimelineEntry> timeline = service.timeline(leadId);

        assertThat(timeline).filteredOn(e -> e.type() == TimelineEventType.REJEU).isEmpty();
        assertThat(timeline).filteredOn(e -> e.type() == TimelineEventType.MORT).hasSize(1);
    }
}

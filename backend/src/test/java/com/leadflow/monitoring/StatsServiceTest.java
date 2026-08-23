package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.monitoring.dto.StatsView;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatsServiceTest {

    @Autowired private StatsService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @AfterEach
    void nettoie() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void compteLesLeadsParStatutZerosCompris() {
        UUID clientId = creeUnClient().getId();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.RULES, "devis", null);
        creeUnLead(clientId, LeadStatus.QUALIFIED, IntentSource.GEMINI, "information", null);

        StatsView stats = service.calcule(clientId, null, null);

        assertThat(stats.leadsParStatut())
                .containsEntry("SYNCED", 2L)
                .containsEntry("QUALIFIED", 1L)
                // Les cinq valeurs sont presentes : un trou dans la carte ferait un trou
                // dans l'ecran, et « aucune ligne » n'est pas « pas de donnee ».
                .containsEntry("ROUTED", 0L)
                .containsEntry("REJECTED", 0L)
                .containsEntry("FAILED", 0L);
    }

    @Test
    void calculeLeTauxDeConversion() {
        UUID clientId = creeUnClient().getId();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);
        creeUnLead(clientId, LeadStatus.QUALIFIED, IntentSource.GEMINI, "devis", null);

        assertThat(service.calcule(clientId, null, null).tauxDeConversion()).isEqualTo(0.5d);
    }

    @Test
    void leTauxDeConversionEstNulSansAucunLead() {
        // Aucune division par zero : l'ecran d'un client qui vient d'etre cree doit
        // s'afficher, pas tomber.
        UUID clientId = creeUnClient().getId();

        StatsView stats = service.calcule(clientId, null, null);

        assertThat(stats.total()).isZero();
        assertThat(stats.tauxDeConversion()).isZero();
    }

    @Test
    void mesureLaPartDuModeDegrade() {
        UUID clientId = creeUnClient().getId();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.RULES, "devis", null);
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.RULES, "devis", null);

        StatsView stats = service.calcule(clientId, null, null);

        // Raison d'etre de la colonne intent_source, ajoutee en F3 et jamais lue depuis :
        // si Gemini tombe en panne de quota, le pipeline continue en silence, et c'est ici
        // que ca se voit.
        assertThat(stats.leadsParSourceDIntention())
                .containsEntry("GEMINI", 1L)
                .containsEntry("RULES", 2L);
    }

    @Test
    void compteLesEvenementsBrutsParStatutDiscardedCompris() {
        UUID clientId = creeUnClient().getId();
        creeUnEvenement(clientId, RawLeadEventStatus.PUBLISHED);
        creeUnEvenement(clientId, RawLeadEventStatus.DISCARDED);

        StatsView stats = service.calcule(clientId, null, null);

        // La sante de la capture : DISCARDED mesure les formulaires mal branches chez un
        // client, que rien d'autre ne montre.
        assertThat(stats.evenementsParStatut())
                .containsEntry("PUBLISHED", 1L)
                .containsEntry("DISCARDED", 1L)
                .containsEntry("RECEIVED", 0L)
                .containsEntry("FAILED", 0L);
    }

    @Test
    void repartitLesIntentionsDetectees() {
        UUID clientId = creeUnClient().getId();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "information", null);

        assertThat(service.calcule(clientId, null, null).leadsParIntention())
                .containsEntry("devis", 2L)
                .containsEntry("information", 1L);
    }

    @Test
    void compteParCommercialEtResoutSonNom() {
        Client client = creeUnClient();
        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Sonia Berger");
        commercial.setEmail("sonia@exemple.fr");
        UUID commercialId = salesRepRepository.saveAndFlush(commercial).getId();

        creeUnLead(client.getId(), LeadStatus.SYNCED, IntentSource.GEMINI, "devis", commercialId);
        creeUnLead(client.getId(), LeadStatus.ROUTED, IntentSource.GEMINI, "devis", commercialId);

        StatsView stats = service.calcule(client.getId(), null, null);

        assertThat(stats.leadsParCommercial()).containsEntry(commercialId.toString(), 2L);
        assertThat(stats.nomsDeCommercial())
                .containsEntry(commercialId.toString(), "Sonia Berger");
    }

    @Test
    void leFiltreClientIsoleLesAgregats() {
        UUID premier = creeUnClient().getId();
        UUID second = creeUnClient().getId();
        creeUnLead(premier, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);
        creeUnLead(second, LeadStatus.SYNCED, IntentSource.GEMINI, "devis", null);

        assertThat(service.calcule(premier, null, null).total()).isEqualTo(1L);
        assertThat(service.calcule(null, null, null).total()).isEqualTo(2L);
    }

    private Client creeUnClient() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client " + UUID.randomUUID());
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        return clientRepository.saveAndFlush(client);
    }

    private void creeUnLead(
            UUID clientId, LeadStatus statut, IntentSource source, String intent, UUID commercial) {
        // lead.raw_event_id porte une cle etrangere : un identifiant invente ne passe pas,
        // et chaque lead a de toute facon son evenement d'origine dans le vrai pipeline.
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(creeUnEvenement(clientId, RawLeadEventStatus.PUBLISHED));
        lead.setEmail(UUID.randomUUID() + "@test.fr");
        lead.setStatus(statut);
        lead.setIntentSource(source);
        lead.setDetectedIntent(intent);
        lead.setScore(50);
        lead.setAssignedSalesRepId(commercial);
        leadRepository.saveAndFlush(lead);
    }

    private UUID creeUnEvenement(UUID clientId, RawLeadEventStatus statut) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "a@b.fr")));
        evenement.setSignature("t=1,v1=" + UUID.randomUUID());
        evenement.setStatus(statut);
        evenement.setReceivedAt(Instant.now());
        return rawLeadEventRepository.saveAndFlush(evenement).getId();
    }
}

package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadQueryServiceTest {

    @Autowired private LeadQueryService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    private UUID clientId;
    private UUID commercialId;

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Nord");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost"));
        Client enregistre = clientRepository.saveAndFlush(client);
        clientId = enregistre.getId();

        SalesRep commercial = new SalesRep();
        commercial.setClient(enregistre);
        commercial.setFullName("Sonia Berger");
        commercial.setEmail("sonia@exemple.fr");
        commercialId = salesRepRepository.saveAndFlush(commercial).getId();

        creeLead("chaud@exemple.fr", LeadStatus.SYNCED, 80, commercialId);
        creeLead("tiede@exemple.fr", LeadStatus.QUALIFIED, 40, null);
        creeLead("froid@exemple.fr", LeadStatus.REJECTED, 10, null);
    }

    private void creeLead(String email, LeadStatus statut, int score, UUID commercial) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", email)));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        rawLeadEventRepository.saveAndFlush(evenement);

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(evenement.getId());
        lead.setEmail(email);
        lead.setCompanyName("Ets " + email);
        lead.setScore(score);
        lead.setStatus(statut);
        lead.setAssignedSalesRepId(commercial);
        leadRepository.saveAndFlush(lead);
    }

    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void rendTousLesLeadsDuClientSansFiltre() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecClientId(clientId), PageRequest.of(0, 25));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactlyInAnyOrder(
                        "chaud@exemple.fr", "tiede@exemple.fr", "froid@exemple.fr");
    }

    @Test
    void filtreParStatut() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecStatuts(List.of(LeadStatus.SYNCED)),
                PageRequest.of(0, 25));

        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactly("chaud@exemple.fr");
    }

    @Test
    void combineScoreMinimalEtRecherche() {
        // Deux predicats a la fois : c'est la composition qui est eprouvee ici, pas chaque
        // filtre isolement.
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecMinScore(30).avecRecherche("tiede"),
                PageRequest.of(0, 25));

        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactly("tiede@exemple.fr");
    }

    @Test
    void larechercheIgnoreLaCasse() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecRecherche("CHAUD@EXEMPLE.FR"),
                PageRequest.of(0, 25));

        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactly("chaud@exemple.fr");
    }

    @Test
    void resoutLesNomsDeClientEtDeCommercial() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecStatuts(List.of(LeadStatus.SYNCED)),
                PageRequest.of(0, 25));

        LeadSummary ligne = page.content().getFirst();
        assertThat(ligne.clientName()).isEqualTo("Agence Nord");
        assertThat(ligne.salesRepName()).isEqualTo("Sonia Berger");
    }

    @Test
    void unLeadSansCommercialNAPasDeNomDeCommercial() {
        // Le cas le plus frequent avant le routage : la resolution des noms ne doit pas
        // tomber sur un identifiant nul.
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecStatuts(List.of(LeadStatus.QUALIFIED)),
                PageRequest.of(0, 25));

        LeadSummary ligne = page.content().getFirst();
        assertThat(ligne.assignedSalesRepId()).isNull();
        assertThat(ligne.salesRepName()).isNull();
        assertThat(ligne.clientName()).isEqualTo("Agence Nord");
    }

    @Test
    void pagine() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecClientId(clientId),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(3);
    }
}

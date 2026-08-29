package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Le cas qui casse une implementation naive : trois boutiques, trois seuils, et des scores
 * qui ne se classent pas de la meme facon selon la boutique.
 *
 * <p>La boutique A a un seuil de 50, la B de 90, et la C n'a pas de bareme du tout — elle
 * herite donc du defaut, 70. Un lead a 60 est chaud chez A et tiede chez B, avec le meme
 * score : une implementation qui appliquerait un seuil global, ou celui de la premiere
 * boutique rencontree, passerait tous les autres tests et echouerait sur celui-ci. La
 * boutique C tient le second piege : le seuil d'une boutique sans document n'est pas zero,
 * c'est le defaut que {@code LeadScorer} applique deja.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class LeadChaudTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private LeadQueryService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;

    private UUID boutiqueA;
    private UUID boutiqueB;
    private UUID boutiqueC;
    private UUID leadDeA;

    @BeforeEach
    void jeuDeDonnees() {
        boutiqueA = creeBoutique("Agence Sud", Map.of("seuilChaud", 50));
        boutiqueB = creeBoutique("Agence Nord", Map.of("seuilChaud", 90));
        boutiqueC = creeBoutique("Agence Ouest", Map.of());

        leadDeA = creeLead(boutiqueA, 60);
        creeLead(boutiqueB, 60);
        creeLead(boutiqueC, 70);
    }

    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void leMemeScoreEstChaudChezLUneEtTiedeChezLAutre() throws Exception {
        mockMvc.perform(get("/api/leads").param("clientId", boutiqueA.toString())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chaud").value(true));

        mockMvc.perform(get("/api/leads").param("clientId", boutiqueB.toString())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chaud").value(false));
    }

    /** Une boutique sans bareme herite du seuil par defaut, jamais de zero. */
    @Test
    void uneBoutiqueSansBaremeUtiliseLeSeuilParDefaut() throws Exception {
        mockMvc.perform(get("/api/leads").param("clientId", boutiqueC.toString())
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].chaud").value(true));
    }

    /** Sans clientId, le filtre doit encore distinguer les trois boutiques. */
    @Test
    void leFiltreChaudTraverseLesBoutiques() throws Exception {
        mockMvc.perform(get("/api/leads").param("chaud", "true")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].clientId")
                        .value(containsInAnyOrder(
                                boutiqueA.toString(), boutiqueC.toString())));
    }

    /** {@code chaud=false} n'est pas {@code chaud=true} inverse : c'est l'absence de filtre. */
    @Test
    void unFiltreChaudAbsentOuFauxNeRetireRien() throws Exception {
        mockMvc.perform(get("/api/leads").header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/leads").param("chaud", "false")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void laFicheDUnLeadPorteLeMemeDrapeau() throws Exception {
        mockMvc.perform(get("/api/leads/" + leadDeA)
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaud").value(true));
    }

    /**
     * Le filtre au niveau du service, sans passer par HTTP : le controleur ne fait que
     * traduire un parametre, mais c'est ici que la carte des seuils se construit.
     */
    @Test
    void leFiltreDuServiceRestreintALaBoutiqueDemandee() {
        PageResponse<LeadSummary> chaudsDeB = service.cherche(
                LeadFilter.vide().avecClientId(boutiqueB).avecChaud(true),
                PageRequest.of(0, 25));

        assertThat(chaudsDeB.content()).isEmpty();

        PageResponse<LeadSummary> chaudsDeA = service.cherche(
                LeadFilter.vide().avecClientId(boutiqueA).avecChaud(true),
                PageRequest.of(0, 25));

        assertThat(chaudsDeA.content())
                .singleElement()
                .satisfies(ligne -> {
                    assertThat(ligne.id()).isEqualTo(leadDeA);
                    assertThat(ligne.chaud()).isTrue();
                });
    }

    private UUID creeBoutique(String nom, Map<String, Object> bareme) {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName(nom);
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        client.setScoringConfig(new HashMap<>(bareme));
        return clientRepository.saveAndFlush(client).getId();
    }

    private UUID creeLead(UUID boutique, int score) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "lead@exemple.fr")));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        rawLeadEventRepository.saveAndFlush(evenement);

        Lead lead = new Lead();
        lead.setClientId(boutique);
        lead.setRawEventId(evenement.getId());
        lead.setEmail("lead-" + UUID.randomUUID() + "@exemple.fr");
        lead.setScore(score);
        lead.setStatus(LeadStatus.QUALIFIED);
        return leadRepository.saveAndFlush(lead).getId();
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

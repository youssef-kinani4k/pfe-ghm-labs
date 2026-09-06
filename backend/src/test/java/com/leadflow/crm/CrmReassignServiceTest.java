package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Ce test porte sur la <b>decision</b> — appeler l'ERP ou acquitter — et non sur la
 * traduction, deja eprouvee dans les tests contractuels des adaptateurs.
 *
 * <p>Le connecteur factice declare le fournisseur {@code "factice"} et non {@code
 * "dolibarr"} : {@link CrmConnectorRegistry} refuse de demarrer si deux connecteurs
 * revendiquent le meme identifiant, et l'adaptateur Dolibarr reel est dans le contexte.
 *
 * <p>{@code @SpringBootTest} et non {@code @DataJpaTest} — les converters de chiffrement
 * sont des {@code @Component}. La base Testcontainers etant partagee par toute la suite,
 * <b>ce test n'efface que les lignes qu'il a creees</b>.
 */
@SpringBootTest(properties = "leadflow.crm.providers.factice.enabled=true")
@Import({TestcontainersConfiguration.class, CrmReassignServiceTest.ConnecteurDeTest.class})
class CrmReassignServiceTest {

    /**
     * Double de test du port. Il remplace l'adaptateur Dolibarr pour que ce test porte sur
     * la decision — appeler ou acquitter — et non sur une traduction deja eprouvee ailleurs.
     */
    static final class ConnecteurFactice implements CrmConnector {

        final List<String> appels = new ArrayList<>();
        boolean utilisateurInconnu;
        boolean echoue;

        @Override
        public String providerId() {
            return "factice";
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
            throw new UnsupportedOperationException("Hors sujet pour ce test");
        }

        @Override
        public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
            appels.add("resolveAssignee");
            return utilisateurInconnu ? null : "9";
        }

        @Override
        public List<CrmSettingSpec> reglagesAttendus() {
            return List.of();
        }

        @Override
        public CrmCheck verifieAcces(CrmTarget cible) {
            throw new UnsupportedOperationException("Hors sujet pour ce test");
        }

        @Override
        public void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible) {
            appels.add("reaffecte");
            if (echoue) {
                throw new CrmSyncException("factice", "ERP injoignable", null);
            }
        }
    }

    @TestConfiguration
    static class ConnecteurDeTest {
        @Bean
        ConnecteurFactice connecteurFactice() {
            return new ConnecteurFactice();
        }
    }

    @Autowired private CrmReassignService service;
    @Autowired private ConnecteurFactice connecteurFactice;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;
    @Autowired private CrmSyncTraceWriter trace;

    private final List<UUID> leadsCrees = new ArrayList<>();
    private final List<UUID> capturesCreees = new ArrayList<>();
    private final List<UUID> commerciauxCrees = new ArrayList<>();
    private final List<UUID> boutiquesCreees = new ArrayList<>();

    @BeforeEach
    void reinitialiseLeConnecteur() {
        connecteurFactice.appels.clear();
        connecteurFactice.utilisateurInconnu = false;
        connecteurFactice.echoue = false;
    }

    /**
     * N'efface que ce que cette classe a cree. Un {@code deleteAll()} non porte casserait les
     * autres classes de la suite selon l'ordre d'execution, de facon invisible en lancement
     * isole. L'ordre suit les cles etrangeres : tentatives, leads, captures, commerciaux,
     * boutiques.
     */
    @AfterEach
    void effaceCeQueCeTestACree() {
        leadsCrees.forEach(id -> attemptRepository.deleteAll(
                attemptRepository.findByLeadIdOrderByAttemptedAtDesc(id)));
        leadsCrees.forEach(leadRepository::deleteById);
        capturesCreees.forEach(rawLeadEventRepository::deleteById);
        commerciauxCrees.forEach(salesRepRepository::deleteById);
        boutiquesCreees.forEach(clientRepository::deleteById);
        leadsCrees.clear();
        capturesCreees.clear();
        commerciauxCrees.clear();
        boutiquesCreees.clear();
    }

    @Test
    void nAppellePasLErpQuandLeLeadNyEstPasEncore() {
        UUID leadId = unLeadRouteJamaisSynchronise();

        service.propage(leadId);

        // Rien a corriger : sa synchronisation future portera deja le bon commercial,
        // versPivot lisant assignedSalesRepId au moment de l'appel.
        assertThat(connecteurFactice.appels).isEmpty();
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(ligne.getErrorMessage()).contains("jamais synchronise");
    }

    @Test
    void nAppellePasLErpQuandIlNeConnaitPasLeCommercial() {
        UUID leadId = unLeadSynchronise();
        connecteurFactice.utilisateurInconnu = true;

        service.propage(leadId);

        assertThat(connecteurFactice.appels).doesNotContain("reaffecte");
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getErrorMessage()).contains("inconnu de l ERP");
        // L'invariant de reaffectationSansEffet : aucune des quatre references n'est posee.
        // Une seule suffirait a faire croire a etatAnterieurPour que l'ERP connait ce lead,
        // et une resynchronisation ulterieure sauterait l'etape correspondante.
        assertThat(ligne.getAccountRef()).isNull();
        assertThat(ligne.getContactRef()).isNull();
        assertThat(ligne.getOpportunityRef()).isNull();
        assertThat(ligne.getAssigneeRef()).isNull();
    }

    @Test
    void distingueLeLeadSansCommercialDuCommercialInconnuDeLErp() {
        // Deux causes, deux messages : « inconnu de l ERP » enverrait l'operateur chercher un
        // utilisateur manquant chez son ERP, alors qu'aucun commercial n'a ete demande.
        UUID leadId = unLeadSynchroniseSansCommercial();

        service.propage(leadId);

        assertThat(connecteurFactice.appels).isEmpty();
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(ligne.getErrorMessage()).contains("sans commercial");
        assertThat(ligne.getErrorMessage()).doesNotContain("inconnu de l ERP");
    }

    @Test
    void memoriseLaReferenceDuCommercialCommeLaSynchronisation() {
        // La resolution est partagee avec CrmSyncService, effet de bord compris : une seule
        // implementation, donc un seul comportement de memorisation a eprouver.
        UUID leadId = unLeadSynchronise();
        UUID salesRepId = leadRepository.findById(leadId).orElseThrow().getAssignedSalesRepId();

        service.propage(leadId);

        assertThat(salesRepRepository.findById(salesRepId).orElseThrow().getCrmRef())
                .isEqualTo("9");
    }

    @Test
    void nAppellePasLErpQuandLeConnecteurDeLaBoutiqueEstIndisponible() {
        // Sans ce cas, la branche qui consulte availableProviders() au lieu de rattraper
        // l'IllegalArgumentException de forProvider ne serait couverte par rien — et un
        // connecteur desactive partirait en DLQ au lieu d'acquitter.
        UUID leadId = unLeadChezUnConnecteurIndisponible();

        assertThatCode(() -> service.propage(leadId)).doesNotThrowAnyException();

        assertThat(connecteurFactice.appels).isEmpty();
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(ligne.getErrorMessage()).contains("Connecteur indisponible");
    }

    @Test
    void corrigeLeResponsableEtTraceLaReference() {
        UUID leadId = unLeadSynchronise();

        service.propage(leadId);

        assertThat(connecteurFactice.appels).contains("reaffecte");
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(ligne.getAssigneeRef()).isEqualTo("9");
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
    }

    @Test
    void laisseRemonterUnEchecTechniqueApresAvoirTrace() {
        UUID leadId = unLeadSynchronise();
        connecteurFactice.echoue = true;

        assertThatThrownBy(() -> service.propage(leadId)).isInstanceOf(CrmSyncException.class);

        // La trace est ecrite AVANT que l'exception ne parte, en REQUIRES_NEW : sans cela
        // elle disparaitrait avec le rollback du consommateur, au moment ou elle sert le
        // plus.
        CrmSyncAttempt ligne = derniereTentative(leadId);
        assertThat(ligne.getStatus()).isEqualTo(CrmSyncAttemptStatus.FAILED);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
    }

    @Test
    void uneReaffectationRateeNeDesynchronisePasLeLead() {
        UUID leadId = unLeadSynchronise();
        connecteurFactice.echoue = true;

        assertThatThrownBy(() -> service.propage(leadId)).isInstanceOf(CrmSyncException.class);

        assertThat(leadRepository.findById(leadId).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.SYNCED);
    }

    /** Un lead attribue mais que l'ERP ne connait pas : aucune ligne crm_sync_attempt. */
    private UUID unLeadRouteJamaisSynchronise() {
        return unLeadAttribue("factice", LeadStatus.ROUTED);
    }

    /** Une boutique dont le fournisseur n'est active par aucune propriete. */
    private UUID unLeadChezUnConnecteurIndisponible() {
        return unLeadAttribue("fournisseur-eteint", LeadStatus.ROUTED);
    }

    /**
     * Un lead deja present chez l'ERP, avec une ligne SUCCESS portant l'ancien responsable
     * "7" : le point de depart d'une propagation qui a quelque chose a corriger.
     */
    private UUID unLeadSynchronise() {
        UUID leadId = unLeadAttribue("factice", LeadStatus.ROUTED);
        trace.succes(
                leadId,
                new CrmSyncResult("factice", "A-1", "C-1", "O-1", "7", null, Instant.now()));
        return leadId;
    }

    /** Le meme, mais sans titulaire : le cas ou il n'y a personne a poser chez l'ERP. */
    private UUID unLeadSynchroniseSansCommercial() {
        UUID leadId = unLeadSynchronise();
        Lead lead = leadRepository.findById(leadId).orElseThrow();
        lead.setAssignedSalesRepId(null);
        leadRepository.saveAndFlush(lead);
        return leadId;
    }

    /**
     * Le commercial porte un {@code crmRef} <b>nul</b>, sans quoi {@code
     * referenceDuCommercial} court-circuiterait {@code resolveAssignee} et le drapeau
     * {@code utilisateurInconnu} n'aurait aucun effet.
     */
    private UUID unLeadAttribue(String providerId, LeadStatus statut) {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test F15");
        client.setHmacSecret("secret");
        client.setCrmProviderId(providerId);
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-erp"));
        client = clientRepository.saveAndFlush(client);
        boutiquesCreees.add(client.getId());

        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail("amina-" + UUID.randomUUID() + "@demo.test");
        UUID salesRepId = salesRepRepository.saveAndFlush(commercial).getId();
        commerciauxCrees.add(salesRepId);

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(client.getId());
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = rawLeadEventRepository.saveAndFlush(evenement).getId();
        capturesCreees.add(rawEventId);

        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setRawEventId(rawEventId);
        lead.setCompanyName("Acme");
        lead.setFirstName("Karim");
        lead.setLastName("Haddad");
        lead.setEmail("karim-" + UUID.randomUUID() + "@acme.test");
        lead.setScore(72);
        lead.setStatus(statut);
        lead.setAssignedSalesRepId(salesRepId);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();
        leadsCrees.add(leadId);
        return leadId;
    }

    /** La ligne la plus recente d'un lead, tous fournisseurs confondus. */
    private CrmSyncAttempt derniereTentative(UUID leadId) {
        return attemptRepository.findByLeadIdOrderByAttemptedAtDesc(leadId).getFirst();
    }
}

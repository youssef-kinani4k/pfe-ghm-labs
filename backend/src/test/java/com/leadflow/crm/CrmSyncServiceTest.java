package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Le connecteur est factice : ce test prouve l'orchestration, pas la traduction ERP.
 * {@code @SpringBootTest} et non {@code @DataJpaTest} — les converters de chiffrement sont
 * des {@code @Component}.
 */
@SpringBootTest(properties = "leadflow.crm.providers.espion.enabled=true")
@Import({TestcontainersConfiguration.class, CrmSyncServiceTest.ConnecteurDeTest.class})
class CrmSyncServiceTest {

    static final class ConnecteurEspion implements CrmConnector {

        CrmLead leadRecu;
        CrmTarget cibleRecue;
        CrmSyncState etatRecu;
        CrmAssignee assigneeRecu;
        boolean echoue;
        boolean echoueSurLeCommercial;

        /**
         * Fournisseur qui n'existe que pour ce test : deux connecteurs ne peuvent pas
         * declarer le meme identifiant, et l'adaptateur Dolibarr reel est dans le contexte.
         */
        @Override
        public String providerId() {
            return "espion";
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
            this.leadRecu = lead;
            this.cibleRecue = target;
            this.etatRecu = previous;
            if (echoue) {
                throw new CrmSyncException("espion", "opportunite refusee", null)
                        .avecEtat(new CrmSyncState("A-1", "C-1", null));
            }
            return new CrmSyncResult("espion", "A-1", "C-1", "O-1", null, Instant.now());
        }

        @Override
        public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
            this.assigneeRecu = assignee;
            if (echoueSurLeCommercial) {
                throw new CrmSyncException("espion", "instance injoignable", null);
            }
            return "U-9";
        }
    }

    @TestConfiguration
    static class ConnecteurDeTest {
        @Bean
        ConnecteurEspion connecteurEspion() {
            return new ConnecteurEspion();
        }
    }

    @Autowired private CrmSyncService service;
    @Autowired private ConnecteurEspion connecteur;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;

    private UUID leadId;
    private UUID salesRepId;
    private String emailDuCommercial;

    @BeforeEach
    void preparerUnLeadComplet() {
        connecteur.echoue = false;
        connecteur.echoueSurLeCommercial = false;
        connecteur.assigneeRecu = null;

        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("espion");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-erp"));
        client = clientRepository.saveAndFlush(client);

        emailDuCommercial = "amina-" + UUID.randomUUID() + "@demo.test";
        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Amina Bensalem");
        commercial.setEmail(emailDuCommercial);
        salesRepId = salesRepRepository.saveAndFlush(commercial).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(client.getId());
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature("sig");
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = rawLeadEventRepository.saveAndFlush(evenement).getId();

        Lead lead = new Lead();
        lead.setClientId(client.getId());
        lead.setRawEventId(rawEventId);
        lead.setCompanyName("Acme");
        lead.setFirstName("Karim");
        lead.setLastName("Haddad");
        lead.setEmail("karim@acme.test");
        lead.setScore(72);
        lead.setStatus(LeadStatus.ROUTED);
        lead.setAssignedSalesRepId(salesRepId);
        leadId = leadRepository.saveAndFlush(lead).getId();
    }

    @Test
    void construitLaCibleDepuisLaConfigurationDechiffreeDuClient() {
        service.synchronise(leadId);

        assertThat(connecteur.cibleRecue.providerId()).isEqualTo("espion");
        assertThat(connecteur.cibleRecue.settings()).containsEntry("baseUrl", "http://erp.test");
        assertThat(connecteur.cibleRecue.settings()).containsEntry("apiKey", "cle-erp");
    }

    @Test
    void traduitLeLeadVersLePivot() {
        service.synchronise(leadId);

        assertThat(connecteur.leadRecu.companyName()).isEqualTo("Acme");
        assertThat(connecteur.leadRecu.email()).isEqualTo("karim@acme.test");
        assertThat(connecteur.leadRecu.score()).isEqualTo(72);
        assertThat(connecteur.leadRecu.assigneeRef()).isEqualTo("U-9");
    }

    @Test
    void resoutPuisMemoriseLaReferenceDuCommercial() {
        service.synchronise(leadId);

        assertThat(connecteur.assigneeRecu.email()).isEqualTo(emailDuCommercial);
        assertThat(salesRepRepository.findById(salesRepId).orElseThrow().getCrmRef())
                .isEqualTo("U-9");
    }

    @Test
    void neResoutPasDeuxFoisLeMemeCommercial() {
        service.synchronise(leadId);
        connecteur.assigneeRecu = null;

        service.synchronise(leadId);

        assertThat(connecteur.assigneeRecu).isNull();
    }

    @Test
    void traceLeSuccesEtPasseLeLeadEnSynced() {
        CrmSyncResult resultat = service.synchronise(leadId);

        assertThat(resultat.opportunityRef()).isEqualTo("O-1");
        assertThat(leadRepository.findById(leadId).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.SYNCED);
        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, "espion");
        assertThat(tentatives).hasSize(1);
        assertThat(tentatives.getFirst().getStatus()).isEqualTo(CrmSyncAttemptStatus.SUCCESS);
        assertThat(tentatives.getFirst().getAccountRef()).isEqualTo("A-1");
    }

    @Test
    void traceLEchecAvecLesReferencesDejaObtenuesPuisRelaieLException() {
        connecteur.echoue = true;

        assertThatThrownBy(() -> service.synchronise(leadId))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("opportunite refusee");

        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, "espion");
        assertThat(tentatives).hasSize(1);
        assertThat(tentatives.getFirst().getStatus()).isEqualTo(CrmSyncAttemptStatus.FAILED);
        assertThat(tentatives.getFirst().getAccountRef()).isEqualTo("A-1");
        assertThat(tentatives.getFirst().getContactRef()).isEqualTo("C-1");
        assertThat(tentatives.getFirst().getOpportunityRef()).isNull();
        assertThat(tentatives.getFirst().getErrorMessage()).contains("opportunite refusee");
        assertThat(leadRepository.findById(leadId).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.FAILED);
    }

    @Test
    void traceLEchecQuandLERPEstInjoignableDesLaResolutionDuCommercial() {
        // resolveAssignee appelle l'ERP : une instance injoignable a ce moment-la doit
        // laisser la meme trace FAILED qu'un echec pendant sync, sans quoi le lead reste
        // ROUTED et disparait des ecrans de suivi.
        connecteur.echoueSurLeCommercial = true;

        assertThatThrownBy(() -> service.synchronise(leadId))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("instance injoignable");

        List<CrmSyncAttempt> tentatives =
                attemptRepository.findByLeadIdAndProviderIdOrderByAttemptedAtDesc(leadId, "espion");
        assertThat(tentatives).hasSize(1);
        assertThat(tentatives.getFirst().getStatus()).isEqualTo(CrmSyncAttemptStatus.FAILED);
        assertThat(tentatives.getFirst().getErrorMessage()).contains("instance injoignable");
        assertThat(leadRepository.findById(leadId).orElseThrow().getStatus())
                .isEqualTo(LeadStatus.FAILED);
    }

    @Test
    void reconstruitLEtatChampParChampSurToutesLesTentatives() {
        connecteur.echoue = true;
        assertThatThrownBy(() -> service.synchronise(leadId)).isInstanceOf(CrmSyncException.class);

        connecteur.echoue = false;
        service.synchronise(leadId);

        assertThat(connecteur.etatRecu.accountRef()).isEqualTo("A-1");
        assertThat(connecteur.etatRecu.contactRef()).isEqualTo("C-1");
        assertThat(connecteur.etatRecu.opportunityRef()).isNull();
    }
}

package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.leadflow.routing.LeadActionRepository;
import com.leadflow.routing.LeadReassignedMessage;
import com.leadflow.routing.ReattributionService;
import com.leadflow.tenant.AssignmentStrategyType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Deux echelles pour ce consommateur. Les deux premiers tests portent sur le cablage — il
 * delegue au service et ne rattrape rien —, sans passer par Spring : {@code ServiceDeTest}
 * remplace {@link CrmReassignService} par un double qui n'implemente que {@code propage}.
 *
 * <p>Le troisieme traverse la chaine entiere, reattribution comprise, et c'est le seul qui
 * attraperait un binding oublie ou un contrat refuse par la liste blanche de paquets.
 *
 * <p>{@code @SpringBootTest} et non {@code @DataJpaTest} : les converters de chiffrement sont
 * des {@code @Component}. La base Testcontainers etant partagee par toute la suite, <b>ce
 * test n'efface que les lignes qu'il a creees</b>.
 */
@SpringBootTest(properties = "leadflow.crm.providers.factice.enabled=true")
@Import({TestcontainersConfiguration.class, CrmReassignListenerTest.ConnecteurDeTest.class})
@TestPropertySource(properties = "leadflow.crm.reassign.listener.enabled=true")
class CrmReassignListenerTest {

    /**
     * Double de {@link CrmReassignService} qui n'implemente que {@code propage} : les deux
     * premiers tests portent sur ce que le consommateur fait de l'appel, pas sur la decision
     * metier, deja eprouvee dans {@code CrmReassignServiceTest}.
     */
    static final class ServiceDeTest extends CrmReassignService {

        final List<UUID> leadsPropages = new ArrayList<>();
        boolean echoue;

        ServiceDeTest() {
            super(null, null, null, null, null);
        }

        @Override
        public void propage(UUID leadId) {
            if (echoue) {
                throw new CrmSyncException("factice", "ERP injoignable", null);
            }
            leadsPropages.add(leadId);
        }
    }

    /** Connecteur qui n'existe que pour le test de bout en bout : "factice" et non un ERP reel. */
    static final class ConnecteurFactice implements CrmConnector {

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
            return "9";
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
            // Reussit toujours : ce test verifie que la chaine transporte le geste jusqu'a la
            // trace, pas les cas d'echec de l'ERP, deja couverts par CrmReassignServiceTest.
        }
    }

    @TestConfiguration
    static class ConnecteurDeTest {
        @Bean
        ConnecteurFactice connecteurFactice() {
            return new ConnecteurFactice();
        }
    }

    private final ServiceDeTest service = new ServiceDeTest();
    private final CrmReassignListener listener = new CrmReassignListener(service);

    @Autowired private ReattributionService reattribution;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;
    @Autowired private CrmSyncTraceWriter trace;
    @Autowired private LeadActionRepository leadActionRepository;

    private final List<UUID> leadsCrees = new ArrayList<>();
    private final List<UUID> capturesCreees = new ArrayList<>();
    private final List<UUID> commerciauxCrees = new ArrayList<>();
    private final List<UUID> boutiquesCreees = new ArrayList<>();

    /**
     * N'efface que ce que cette classe a cree, dans l'ordre des cles etrangeres : tentatives,
     * actions (la reattribution en ecrit une), leads, captures, commerciaux, boutiques.
     */
    @AfterEach
    void effaceCeQueCeTestACree() {
        leadsCrees.forEach(id -> attemptRepository.deleteAll(
                attemptRepository.findByLeadIdOrderByAttemptedAtDesc(id)));
        leadsCrees.forEach(id -> leadActionRepository.deleteAll(
                leadActionRepository.findByLeadIdOrderByCreatedAtAsc(id)));
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
    void deleguerAuServiceEtRienDAutre() {
        UUID leadId = UUID.randomUUID();

        listener.recoit(new LeadReassignedMessage(
                leadId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now()));

        assertThat(service.leadsPropages).containsExactly(leadId);
    }

    @Test
    void laisseRemonterLExceptionPourQueLaFileReessaie() {
        service.echoue = true;

        assertThatThrownBy(() -> listener.recoit(new LeadReassignedMessage(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), Instant.now())))
                .isInstanceOf(CrmSyncException.class);
    }

    @Test
    void deLaReattributionJusquALaLigneDeTrace() throws InterruptedException {
        UUID leadId = unLeadSynchronise();
        UUID second = unCommercial(boutique, "Yanis Roux");

        reattribution.reattribue(leadId, second, "Secteur mal decoupe", "admin");

        // Le consommateur travaille en parallele : on attend la ligne, on ne la suppose pas.
        // Pas d'Awaitility ici : la dependance n'est pas declaree dans pom.xml, et l'ajouter
        // pour ce seul test serait disproportionne. Boucle bornee, comme les autres tests
        // asynchrones qui n'en disposent pas.
        CrmSyncAttempt ligne = attendLaTraceDeReaffectation(leadId);
        assertThat(ligne.getNature()).isEqualTo(CrmSyncAttemptNature.REAFFECTATION);
    }

    private Client boutique;

    /**
     * Un lead deja present chez l'ERP "factice", avec une ligne SUCCESS anterieure : le point
     * de depart d'une reattribution qui a quelque chose a corriger. Pose le champ
     * {@link #boutique} en effet de bord pour que le test puisse y creer un second commercial.
     */
    private UUID unLeadSynchronise() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique F15 bout en bout");
        client.setHmacSecret("secret");
        client.setCrmProviderId("factice");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-erp"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        boutique = clientRepository.saveAndFlush(client);
        boutiquesCreees.add(boutique.getId());

        UUID premier = unCommercial(boutique, "Amina Bensalem");

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique.getId());
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = rawLeadEventRepository.saveAndFlush(evenement).getId();
        capturesCreees.add(rawEventId);

        Lead lead = new Lead();
        lead.setClientId(boutique.getId());
        lead.setRawEventId(rawEventId);
        lead.setEmail("karim-" + UUID.randomUUID() + "@acme.test");
        lead.setScore(72);
        lead.setStatus(LeadStatus.SYNCED);
        lead.setAssignedSalesRepId(premier);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();
        leadsCrees.add(leadId);

        trace.succes(
                leadId,
                new CrmSyncResult("factice", "A-1", "C-1", "O-1", "7", null, Instant.now()));
        return leadId;
    }

    private UUID unCommercial(Client client, String nom) {
        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName(nom);
        commercial.setEmail(nom.toLowerCase().replace(" ", ".") + "-" + UUID.randomUUID()
                + "@demo.test");
        UUID id = salesRepRepository.saveAndFlush(commercial).getId();
        commerciauxCrees.add(id);
        return id;
    }

    /**
     * Boucle bornee sur la trace de reaffectation d'un lead : 50 essais espaces de 200 ms, soit
     * 10 secondes au plus, le meme delai que les tests qui utilisent Awaitility ailleurs dans la
     * suite.
     */
    private CrmSyncAttempt attendLaTraceDeReaffectation(UUID leadId) throws InterruptedException {
        for (int essai = 0; essai < 50; essai++) {
            List<CrmSyncAttempt> tentatives =
                    attemptRepository.findByLeadIdOrderByAttemptedAtDesc(leadId);
            if (!tentatives.isEmpty()
                    && tentatives.get(0).getNature() == CrmSyncAttemptNature.REAFFECTATION) {
                return tentatives.get(0);
            }
            Thread.sleep(200);
        }
        throw new AssertionError(
                "Aucune trace de reaffectation apres 10 secondes pour le lead " + leadId);
    }
}

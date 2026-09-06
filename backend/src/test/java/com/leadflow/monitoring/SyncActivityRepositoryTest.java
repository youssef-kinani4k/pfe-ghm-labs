package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptNature;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * L'ecran Connecteurs compte l'activite reelle des ERP. Depuis F15, {@code crm_sync_attempt}
 * ne raconte plus seulement des synchronisations : ce test verrouille le fait que les lignes
 * de reaffectation en sont exclues.
 *
 * <p>Le fournisseur est propre a cette classe et le nettoyage est porte : la base
 * Testcontainers est partagee par toute la suite.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SyncActivityRepositoryTest {

    private static final String FOURNISSEUR = "activite-de-test";

    @Autowired private SyncActivityRepository activite;
    @Autowired private CrmSyncAttemptRepository tentatives;
    @Autowired private LeadRepository leads;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private ClientRepository clients;

    private final List<UUID> tentativesCreees = new ArrayList<>();
    private final List<UUID> leadsCrees = new ArrayList<>();
    private final List<UUID> capturesCreees = new ArrayList<>();
    private final List<UUID> boutiquesCreees = new ArrayList<>();

    @AfterEach
    void effaceCeQueCeTestACree() {
        tentativesCreees.forEach(tentatives::deleteById);
        leadsCrees.forEach(leads::deleteById);
        capturesCreees.forEach(evenements::deleteById);
        boutiquesCreees.forEach(clients::deleteById);
        tentativesCreees.clear();
        leadsCrees.clear();
        capturesCreees.clear();
        boutiquesCreees.clear();
    }

    @Test
    void neCompteQueLesSynchronisationsParFournisseur() {
        Lead lead = unLead();
        tentative(lead, CrmSyncAttemptStatus.SUCCESS, CrmSyncAttemptNature.SYNCHRONISATION);
        tentative(lead, CrmSyncAttemptStatus.FAILED, CrmSyncAttemptNature.SYNCHRONISATION);
        // Corriger un responsable n'est pas synchroniser un lead : ces deux lignes ne
        // doivent bouger aucun des deux compteurs de l'ecran Connecteurs.
        tentative(lead, CrmSyncAttemptStatus.SUCCESS, CrmSyncAttemptNature.REAFFECTATION);
        tentative(lead, CrmSyncAttemptStatus.FAILED, CrmSyncAttemptNature.REAFFECTATION);

        SyncActivityRepository.ActiviteParFournisseur ligne =
                activite.activiteParFournisseur().stream()
                        .filter(l -> FOURNISSEUR.equals(l.getProviderId()))
                        .findFirst()
                        .orElseThrow();

        assertThat(ligne.getSucces()).isEqualTo(1L);
        assertThat(ligne.getEchecs()).isEqualTo(1L);
    }

    @Test
    void neCompteQueLesSynchronisationsParClient() {
        Lead lead = unLead();
        tentative(lead, CrmSyncAttemptStatus.SUCCESS, CrmSyncAttemptNature.SYNCHRONISATION);
        tentative(lead, CrmSyncAttemptStatus.SUCCESS, CrmSyncAttemptNature.REAFFECTATION);

        SyncActivityRepository.ActiviteParClient ligne = activite.activiteParClient().stream()
                .filter(l -> FOURNISSEUR.equals(l.getProviderId()))
                .findFirst()
                .orElseThrow();

        assertThat(ligne.getClientId()).isEqualTo(lead.getClientId());
        assertThat(ligne.getSucces()).isEqualTo(1L);
        assertThat(ligne.getEchecs()).isZero();
    }

    @Test
    void leDernierMessageDEchecIgnoreLesReaffectations() {
        // Sinon l'ecran afficherait « 0 echec » et, juste a cote, le message d'une
        // reaffectation ratee que ce compteur ne compte plus.
        Lead lead = unLead();
        tentative(lead, CrmSyncAttemptStatus.FAILED, CrmSyncAttemptNature.SYNCHRONISATION);
        tentative(lead, CrmSyncAttemptStatus.FAILED, CrmSyncAttemptNature.REAFFECTATION);

        List<CrmSyncAttempt> dernier =
                activite.findTop1ByProviderIdAndStatusAndNatureOrderByAttemptedAtDesc(
                        FOURNISSEUR,
                        CrmSyncAttemptStatus.FAILED,
                        CrmSyncAttemptNature.SYNCHRONISATION);

        assertThat(dernier).singleElement().satisfies(ligne -> assertThat(ligne.getNature())
                .isEqualTo(CrmSyncAttemptNature.SYNCHRONISATION));
    }

    private Lead unLead() {
        Client boutique = new Client();
        boutique.setName("Boutique activite");
        boutique.setPublicKey("pk-" + UUID.randomUUID());
        boutique.setHmacSecret("secret");
        boutique.setCrmProviderId(FOURNISSEUR);
        boutique.setCrmConfig(Map.of());
        boutique = clients.saveAndFlush(boutique);
        boutiquesCreees.add(boutique.getId());

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(boutique.getId());
        evenement.setSource("test");
        evenement.setPayload(Map.of("email", "a@b.fr"));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        UUID rawEventId = evenements.saveAndFlush(evenement).getId();
        capturesCreees.add(rawEventId);

        Lead lead = new Lead();
        lead.setClientId(boutique.getId());
        lead.setRawEventId(rawEventId);
        lead.setEmail("prospect-" + UUID.randomUUID() + "@test.local");
        lead.setScore(50);
        lead.setStatus(LeadStatus.QUALIFIED);
        lead = leads.saveAndFlush(lead);
        leadsCrees.add(lead.getId());
        return lead;
    }

    private void tentative(Lead lead, CrmSyncAttemptStatus statut, CrmSyncAttemptNature nature) {
        CrmSyncAttempt a = new CrmSyncAttempt();
        a.setLeadId(lead.getId());
        a.setProviderId(FOURNISSEUR);
        a.setStatus(statut);
        a.setNature(nature);
        a.setAttemptedAt(Instant.now());
        tentativesCreees.add(tentatives.saveAndFlush(a).getId());
    }
}

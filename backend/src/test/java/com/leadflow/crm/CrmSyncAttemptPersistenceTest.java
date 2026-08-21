package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrmSyncAttemptPersistenceTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RawLeadEventRepository rawLeadEventRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private CrmSyncAttemptRepository attemptRepository;

    private UUID leadEnregistre(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        Client enregistre = clientRepository.saveAndFlush(client);

        RawLeadEvent event = new RawLeadEvent();
        event.setClientId(enregistre.getId());
        event.setSource("formulaire-contact");
        event.setPayload(Map.of("email", "prospect@exemple.test"));
        event.setSignature("signature-hmac");
        RawLeadEvent evenement = rawLeadEventRepository.saveAndFlush(event);

        Lead lead = new Lead();
        lead.setClientId(enregistre.getId());
        lead.setRawEventId(evenement.getId());
        lead.setEmail("prospect@exemple.test");
        return leadRepository.saveAndFlush(lead).getId();
    }

    private CrmSyncAttempt tentative(UUID leadId, CrmSyncAttemptStatus statut, String accountRef) {
        CrmSyncAttempt attempt = new CrmSyncAttempt();
        attempt.setLeadId(leadId);
        attempt.setProviderId("dolibarr");
        attempt.setStatus(statut);
        attempt.setAccountRef(accountRef);
        return attempt;
    }

    @Test
    void enregistreUneTentativeAvecSonHorodatage() {
        UUID leadId = leadEnregistre("cle-sync-1");

        CrmSyncAttempt enregistree = attemptRepository.saveAndFlush(
                tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1042"));

        assertThat(enregistree.getId()).isNotNull();
        assertThat(enregistree.getAttemptedAt()).isNotNull();
    }

    @Test
    void retrouveLaDerniereTentativeReussieEtIgnoreLesEchecs() {
        UUID leadId = leadEnregistre("cle-sync-2");

        // Horodatages fixes explicitement pour ne pas dependre de l'horloge systeme :
        // deux ecritures rapprochees peuvent tomber sur le meme instant en millisecondes.
        Instant ancienneDate = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant recenteDate = Instant.now();

        CrmSyncAttempt ancienneReussie = tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1000");
        ancienneReussie.setAttemptedAt(ancienneDate);
        attemptRepository.saveAndFlush(ancienneReussie);

        CrmSyncAttempt echec = tentative(leadId, CrmSyncAttemptStatus.FAILED, null);
        echec.setAttemptedAt(recenteDate);
        attemptRepository.saveAndFlush(echec);

        CrmSyncAttempt recenteReussie = tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1042");
        recenteReussie.setAttemptedAt(recenteDate);
        attemptRepository.saveAndFlush(recenteReussie);

        CrmSyncAttempt derniere = attemptRepository
                .findFirstByLeadIdAndStatusOrderByAttemptedAtDesc(leadId, CrmSyncAttemptStatus.SUCCESS)
                .orElseThrow();

        // Prouve les deux moities du contrat : le FAILED est ignore, et parmi les SUCCESS
        // c'est le plus recent (1042, pas l'ancien 1000) qui est retrouve.
        assertThat(derniere.getAccountRef()).isEqualTo("1042");
    }

    @Test
    void conserveTouteLHistoriqueDUnLead() {
        UUID leadId = leadEnregistre("cle-sync-3");
        attemptRepository.saveAndFlush(tentative(leadId, CrmSyncAttemptStatus.FAILED, null));
        attemptRepository.saveAndFlush(tentative(leadId, CrmSyncAttemptStatus.SUCCESS, "1043"));

        assertThat(attemptRepository.findByLeadIdOrderByAttemptedAtDesc(leadId)).hasSize(2);
    }
}

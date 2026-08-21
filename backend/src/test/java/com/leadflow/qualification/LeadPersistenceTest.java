package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
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
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadPersistenceTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RawLeadEventRepository rawLeadEventRepository;

    @Autowired
    private LeadRepository leadRepository;

    private Client clientEnregistre(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        return clientRepository.saveAndFlush(client);
    }

    private RawLeadEvent evenementEnregistre(UUID clientId) {
        RawLeadEvent event = new RawLeadEvent();
        event.setClientId(clientId);
        event.setSource("formulaire-contact");
        event.setPayload(Map.of("email", "prospect@exemple.test", "message", "Demande de devis"));
        event.setSignature("signature-hmac");
        return rawLeadEventRepository.saveAndFlush(event);
    }

    private Lead lead(UUID clientId, UUID rawEventId) {
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail("prospect@exemple.test");
        lead.setDetectedIntent("DEMANDE_DEVIS");
        lead.setIntentSource(IntentSource.RULES);
        lead.setScore(72);
        return lead;
    }

    @Test
    void enregistreUnLeadAvecSonStatutParDefaut() {
        Client client = clientEnregistre("cle-lead-1");
        RawLeadEvent event = evenementEnregistre(client.getId());

        Lead enregistre = leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        assertThat(enregistre.getId()).isNotNull();
        assertThat(enregistre.getStatus()).isEqualTo(LeadStatus.QUALIFIED);
        assertThat(enregistre.getIntentSource()).isEqualTo(IntentSource.RULES);
    }

    @Test
    void conserveLePayloadJsonDeLEvenementBrut() {
        Client client = clientEnregistre("cle-lead-2");
        RawLeadEvent event = evenementEnregistre(client.getId());

        RawLeadEvent relu = rawLeadEventRepository.findById(event.getId()).orElseThrow();

        assertThat(relu.getPayload()).containsEntry("email", "prospect@exemple.test");
        assertThat(relu.getReceivedAt()).isNotNull();
    }

    @Test
    void refuseDeuxLeadsPourUnMemeEvenementBrut() {
        Client client = clientEnregistre("cle-lead-3");
        RawLeadEvent event = evenementEnregistre(client.getId());
        leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        assertThatThrownBy(
                        () -> leadRepository.saveAndFlush(lead(client.getId(), event.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void detecteUnDoublonDansLaFenetreDeDeduplication() {
        Client client = clientEnregistre("cle-lead-4");
        RawLeadEvent event = evenementEnregistre(client.getId());
        leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        boolean doublon = leadRepository.existsByClientIdAndEmailAndCreatedAtAfter(
                client.getId(), "prospect@exemple.test", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(doublon).isTrue();
    }

    @Test
    void retrouveUnLeadParSonEvenementBrut() {
        Client client = clientEnregistre("cle-lead-5");
        RawLeadEvent event = evenementEnregistre(client.getId());
        leadRepository.saveAndFlush(lead(client.getId(), event.getId()));

        assertThat(leadRepository.findByRawEventId(event.getId())).isPresent();
    }
}

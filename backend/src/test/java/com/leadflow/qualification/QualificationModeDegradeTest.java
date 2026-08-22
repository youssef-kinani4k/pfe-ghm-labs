package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Gemini est actif mais injoignable : {@code base-url} pointe vers un port mort, donc la
 * connexion est refusee sans qu'aucun paquet ne quitte la machine. Le lead doit etre
 * qualifie malgre tout, avec {@code intent_source = RULES}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.intent.gemini.enabled=true",
        "leadflow.intent.gemini.api-key=cle-de-test",
        "leadflow.intent.gemini.base-url=http://localhost:1/",
        "leadflow.intent.gemini.connect-timeout=1s",
        "leadflow.intent.gemini.read-timeout=1s"})
class QualificationModeDegradeTest {

    @Autowired private LeadQualificationService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;

    /**
     * La base est partagee par toute la suite : laisser des leads derriere soi ferait
     * echouer la classe suivante qui vide {@code raw_lead_event} sans passer par les leads.
     */
    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
    }

    @Test
    void qualifieEnModeReglesQuandLApiEstInjoignable() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        UUID clientId = clientRepository.save(client).getId();

        RawLeadEvent brut = new RawLeadEvent();
        brut.setClientId(clientId);
        brut.setSource("formulaire-devis");
        brut.setPayload(new HashMap<>(Map.of(
                "email", "karim@acme.test", "message", "Je veux un devis")));
        brut.setSignature("t=1,v1=" + UUID.randomUUID());
        brut.setStatus(RawLeadEventStatus.PUBLISHED);
        UUID eventId = rawLeadEventRepository.save(brut).getId();

        Lead lead = service.qualifie(eventId).orElseThrow();

        assertThat(lead.getStatus()).isEqualTo(LeadStatus.QUALIFIED);
        assertThat(lead.getDetectedIntent()).isEqualTo(LeadIntent.DEVIS.name());
        assertThat(lead.getIntentSource()).isEqualTo(IntentSource.RULES);
    }
}

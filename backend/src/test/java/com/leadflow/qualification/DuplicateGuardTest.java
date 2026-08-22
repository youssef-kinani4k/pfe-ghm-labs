package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche JPA n'inclut pas les
 * {@code @Component}, donc les converters de chiffrement de {@code Client} ne seraient pas
 * injectes.
 *
 * <p>Chaque lead pointe vers un vrai {@code raw_lead_event} : la colonne
 * {@code lead.raw_event_id} porte une cle etrangere, et un identifiant tire au hasard
 * echouerait sur cette cle avant d'atteindre la contrainte d'unicite que les tests visent.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DuplicateGuardTest {

    @Autowired private DuplicateGuard garde;
    @Autowired private LeadWriter writer;
    @Autowired private LeadRepository leadRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;

    private UUID clientId;

    @BeforeEach
    void preparation() {
        // Les leads d'abord : l'ordre inverse violerait la cle etrangere.
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost", "apiKey", "x"));
        clientId = clientRepository.saveAndFlush(client).getId();
    }

    /** Insere un evenement brut reel et rend son identifiant. */
    private UUID evenement() {
        RawLeadEvent event = new RawLeadEvent();
        event.setClientId(clientId);
        event.setSource("formulaire-contact");
        event.setPayload(Map.of("email", "karim@acme.test"));
        event.setSignature("signature-" + UUID.randomUUID());
        return rawLeadEventRepository.saveAndFlush(event).getId();
    }

    private Lead lead(String email, UUID rawEventId) {
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(rawEventId);
        lead.setEmail(email);
        lead.setScore(0);
        lead.setStatus(LeadStatus.QUALIFIED);
        return lead;
    }

    @Test
    void neVoitPasDeDoublonSurUneBaseVide() {
        assertThat(garde.estDoublon(clientId, "karim@acme.test")).isFalse();
    }

    @Test
    void voitUnDoublonQuandUnLeadRecentPorteLeMemeEmail() {
        writer.insere(lead("karim@acme.test", evenement()));

        assertThat(garde.estDoublon(clientId, "karim@acme.test")).isTrue();
    }

    @Test
    void neVoitPasDeDoublonPourUnAutreClient() {
        writer.insere(lead("karim@acme.test", evenement()));

        assertThat(garde.estDoublon(UUID.randomUUID(), "karim@acme.test")).isFalse();
    }

    @Test
    void neVoitPasDeDoublonPourUnAutreEmail() {
        writer.insere(lead("karim@acme.test", evenement()));

        assertThat(garde.estDoublon(clientId, "autre@acme.test")).isFalse();
    }

    @Test
    void refuseUnSecondLeadPourLeMemeEvenementBrut() {
        UUID evenement = evenement();
        writer.insere(lead("karim@acme.test", evenement));

        assertThatThrownBy(() -> writer.insere(lead("karim@acme.test", evenement)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laisseLAppelantRelireApresUneViolationDeContrainte() {
        UUID evenement = evenement();
        writer.insere(lead("karim@acme.test", evenement));

        try {
            writer.insere(lead("karim@acme.test", evenement));
        } catch (DataIntegrityViolationException attendue) {
            // La transaction du writer est isolee : celle-ci reste utilisable.
        }

        assertThat(leadRepository.findByRawEventId(evenement)).isPresent();
    }
}

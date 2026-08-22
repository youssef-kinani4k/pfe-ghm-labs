package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * L'idempotence du webhook est portee par un index unique et non par une verification
 * applicative : deux requetes concurrentes passeraient toutes les deux un simple
 * « existe-t-il deja ? ».
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RawLeadEventIdempotenceTest {

    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;

    private UUID clientEnregistre() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        return clientRepository.saveAndFlush(client).getId();
    }

    private RawLeadEvent evenement(UUID clientId, String signature) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature(signature);
        evenement.setReceivedAt(Instant.now());
        return evenement;
    }

    @Test
    void refuseDeuxEvenementsDeMemeSignaturePourUnMemeClient() {
        UUID clientId = clientEnregistre();
        rawLeadEventRepository.saveAndFlush(evenement(clientId, "t=1755820000,v1=abcdef"));

        assertThatThrownBy(() -> rawLeadEventRepository
                        .saveAndFlush(evenement(clientId, "t=1755820000,v1=abcdef")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accepteLaMemeSignaturePourDeuxClientsDifferents() {
        // Deux clients ont des secrets differents : une collision de signature entre eux
        // n'a aucune signification, l'unicite ne doit pas etre globale.
        UUID premier = clientEnregistre();
        UUID second = clientEnregistre();

        rawLeadEventRepository.saveAndFlush(evenement(premier, "t=1755820000,v1=abcdef"));
        RawLeadEvent chezLautre =
                rawLeadEventRepository.saveAndFlush(evenement(second, "t=1755820000,v1=abcdef"));

        assertThat(chezLautre.getId()).isNotNull();
    }

    @Test
    void retrouveUnEvenementParClientEtSignature() {
        UUID clientId = clientEnregistre();
        RawLeadEvent enregistre =
                rawLeadEventRepository.saveAndFlush(evenement(clientId, "t=1755820001,v1=123456"));

        assertThat(rawLeadEventRepository.findByClientIdAndSignature(
                        clientId, "t=1755820001,v1=123456"))
                .get()
                .extracting(RawLeadEvent::getId)
                .isEqualTo(enregistre.getId());
    }

    @Test
    void listeLesEvenementsNonPubliesPlusVieuxQueLaLimite() {
        UUID clientId = clientEnregistre();
        RawLeadEvent ancien = evenement(clientId, "t=1755820002,v1=ancien");
        ancien.setReceivedAt(Instant.now().minusSeconds(600));
        rawLeadEventRepository.saveAndFlush(ancien);

        RawLeadEvent recent = evenement(clientId, "t=1755820003,v1=recent");
        rawLeadEventRepository.saveAndFlush(recent);

        RawLeadEvent publie = evenement(clientId, "t=1755820004,v1=publie");
        publie.setReceivedAt(Instant.now().minusSeconds(600));
        publie.setStatus(RawLeadEventStatus.PUBLISHED);
        rawLeadEventRepository.saveAndFlush(publie);

        assertThat(rawLeadEventRepository.findByStatusInAndReceivedAtBefore(
                        List.of(RawLeadEventStatus.RECEIVED, RawLeadEventStatus.FAILED),
                        Instant.now().minusSeconds(60)))
                .extracting(RawLeadEvent::getId)
                .containsExactly(ancien.getId());
    }
}

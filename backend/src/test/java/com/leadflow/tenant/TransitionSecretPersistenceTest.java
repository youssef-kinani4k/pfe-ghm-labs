package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.leadflow.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Les deux colonnes de transition existent, se relisent, et le secret precedent est chiffre
 * au repos comme le courant : le lire en SQL brut ne doit pas rendre le clair.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransitionSecretPersistenceTest {

    private static final String ANCIEN = "ancien-secret-tres-reconnaissable";

    @Autowired private ClientRepository clients;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void nettoie() {
        clients.deleteAll();
    }

    @Test
    void relitLaFenetreDeTransitionEtChiffreLAncienSecret() {
        Instant expiration = Instant.now().plus(24, ChronoUnit.HOURS);
        Client boutique = new Client();
        boutique.setName("Boutique en transition");
        boutique.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        boutique.setHmacSecret("secret-courant");
        boutique.setPreviousHmacSecret(ANCIEN);
        boutique.setPreviousSecretExpiresAt(expiration);
        boutique.setCrmProviderId("dolibarr");
        boutique.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        boutique.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        boutique.setActive(true);
        UUID id = clients.save(boutique).getId();

        Client relu = clients.findById(id).orElseThrow();
        assertThat(relu.getPreviousHmacSecret()).isEqualTo(ANCIEN);
        assertThat(relu.getPreviousSecretExpiresAt())
                .isCloseTo(expiration, within(1, ChronoUnit.SECONDS));

        String enBase = jdbc.queryForObject(
                "SELECT previous_hmac_secret FROM client WHERE id = ?", String.class, id);
        assertThat(enBase).isNotNull().doesNotContain(ANCIEN);
    }

    @Test
    void uneBoutiqueSansTransitionPorteDeuxColonnesNulles() {
        Client boutique = new Client();
        boutique.setName("Boutique ordinaire");
        boutique.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        boutique.setHmacSecret("secret-courant");
        boutique.setCrmProviderId("dolibarr");
        boutique.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        boutique.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        boutique.setActive(true);

        Client relu = clients.findById(clients.save(boutique).getId()).orElseThrow();

        assertThat(relu.getPreviousHmacSecret()).isNull();
        assertThat(relu.getPreviousSecretExpiresAt()).isNull();
    }
}

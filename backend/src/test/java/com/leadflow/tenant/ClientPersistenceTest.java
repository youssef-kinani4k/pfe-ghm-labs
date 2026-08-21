package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Utilise {@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche JPA n'inclut
 * pas les beans {@code @Component}, donc les converters de chiffrement ne seraient pas
 * cables et le test ne prouverait rien.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClientPersistenceTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Client nouveauClient(String publicKey) {
        Client client = new Client();
        client.setPublicKey(publicKey);
        client.setName("Site de demonstration");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle-dolibarr"));
        client.setScoringConfig(Map.of("seuilChaud", 80));
        return client;
    }

    @Test
    void attribueUnIdentifiantEtDesHorodatages() {
        Client enregistre = clientRepository.saveAndFlush(nouveauClient("cle-publique-1"));

        assertThat(enregistre.getId()).isNotNull();
        assertThat(enregistre.getCreatedAt()).isNotNull();
        assertThat(enregistre.getUpdatedAt()).isNotNull();
    }

    @Test
    void relitLeSecretEtLaConfigurationEnClair() {
        Client enregistre = clientRepository.saveAndFlush(nouveauClient("cle-publique-2"));
        clientRepository.flush();

        Client relu = clientRepository.findById(enregistre.getId()).orElseThrow();

        assertThat(relu.getHmacSecret()).isEqualTo("secret-de-signature");
        assertThat(relu.getCrmConfig()).containsEntry("apiKey", "cle-dolibarr");
        assertThat(relu.getAssignmentStrategy()).isEqualTo(AssignmentStrategyType.ROUND_ROBIN);
    }

    @Test
    void neStockeAucunSecretEnClairDansLaBase() {
        Client enregistre = clientRepository.saveAndFlush(nouveauClient("cle-publique-3"));

        String secretEnBase = jdbcTemplate.queryForObject(
                "SELECT hmac_secret FROM client WHERE id = ?", String.class, enregistre.getId());
        String configEnBase = jdbcTemplate.queryForObject(
                "SELECT crm_config FROM client WHERE id = ?", String.class, enregistre.getId());

        assertThat(secretEnBase).doesNotContain("secret-de-signature");
        assertThat(configEnBase).doesNotContain("cle-dolibarr").doesNotContain("apiKey");
    }

    @Test
    void resoutUnClientParSaClePublique() {
        clientRepository.saveAndFlush(nouveauClient("cle-publique-4"));

        assertThat(clientRepository.findByPublicKeyAndActiveTrue("cle-publique-4")).isPresent();
    }

    @Test
    void ignoreUnClientDesactive() {
        Client desactive = nouveauClient("cle-publique-5");
        desactive.setActive(false);
        clientRepository.saveAndFlush(desactive);

        assertThat(clientRepository.findByPublicKeyAndActiveTrue("cle-publique-5")).isEmpty();
    }
}

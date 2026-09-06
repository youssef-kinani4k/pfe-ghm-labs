package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * La fenetre de transition se prouve sur le webhook, pas sur une colonne : un lead signe
 * avec l'ancien secret est accepte tant qu'elle court, refuse des qu'elle est close.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FenetreDeTransitionTest {

    private static final String COURANT = "secret-courant-de-la-boutique";
    private static final String ANCIEN = "ancien-secret-de-la-boutique";

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRepository clients;
    @Autowired private RawLeadEventRepository evenements;
    @Autowired private JdbcTemplate jdbc;

    /** Les boutiques creees par cette classe, et elles seules. */
    private final List<UUID> creees = new ArrayList<>();

    /**
     * Ne supprime que ce que cette classe a cree.
     *
     * <p>La base Testcontainers est partagee par toutes les classes de test, et le schema
     * enchaine {@code lead} vers {@code raw_lead_event} vers {@code client} sans cascade. Un
     * {@code deleteAll()} global echoue donc des qu'une autre classe a laisse une ligne
     * derriere elle, et l'echec depend de l'ordre d'execution — vert en local, rouge en
     * integration continue.
     */
    @AfterEach
    void nettoie() {
        creees.forEach(id -> jdbc.update("delete from raw_lead_event where client_id = ?", id));
        clients.deleteAllById(creees);
        creees.clear();
    }

    @Test
    void pendantLaFenetreLAncienSecretSigneEncoreEtLaLigneLeDit() throws Exception {
        Client boutique = creeUneBoutique(Instant.now().plus(1, ChronoUnit.HOURS));

        envoieUnLeadSigne(boutique.getPublicKey(), ANCIEN).andExpect(status().isAccepted());

        RawLeadEvent ligne = evenements.findAll().getFirst();
        assertThat(ligne.isSignedWithPreviousSecret()).isTrue();
    }

    @Test
    void pendantLaFenetreLeSecretCourantSigneSansEtreMarque() throws Exception {
        Client boutique = creeUneBoutique(Instant.now().plus(1, ChronoUnit.HOURS));

        envoieUnLeadSigne(boutique.getPublicKey(), COURANT).andExpect(status().isAccepted());

        assertThat(evenements.findAll().getFirst().isSignedWithPreviousSecret()).isFalse();
    }

    @Test
    void unefoisLaFenetreCloseLAncienSecretEstRefuse() throws Exception {
        // Expiree d'une seconde : c'est la comparaison de date, et rien d'autre, qui
        // distingue ce cas du precedent.
        Client boutique = creeUneBoutique(Instant.now().minusSeconds(1));

        envoieUnLeadSigne(boutique.getPublicKey(), ANCIEN)
                .andExpect(status().isUnauthorized());
        assertThat(evenements.findAll()).isEmpty();
    }

    @Test
    void sansTransitionSeulLeSecretCourantSigne() throws Exception {
        Client boutique = creeUneBoutique(null);

        envoieUnLeadSigne(boutique.getPublicKey(), ANCIEN)
                .andExpect(status().isUnauthorized());
        envoieUnLeadSigne(boutique.getPublicKey(), COURANT).andExpect(status().isAccepted());
    }

    /** {@code expiration} nulle cree une boutique hors transition. */
    private Client creeUneBoutique(Instant expiration) {
        Client client = new Client();
        client.setName("Boutique en transition");
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret(COURANT);
        if (expiration != null) {
            client.setPreviousHmacSecret(ANCIEN);
            client.setPreviousSecretExpiresAt(expiration);
        }
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        Client sauvegardee = clients.save(client);
        creees.add(sauvegardee.getId());
        return sauvegardee;
    }

    private ResultActions envoieUnLeadSigne(String clePublique, String secret) throws Exception {
        String corps = "{\"source\":\"test\",\"email\":\"prospect@test.fr\"}";
        long horodatage = Instant.now().getEpochSecond();
        return mockMvc.perform(post("/api/webhooks/leads/" + clePublique)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Leadflow-Signature",
                        "t=" + horodatage + ",v1=" + hmacHex(secret, horodatage + "." + corps))
                .content(corps));
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

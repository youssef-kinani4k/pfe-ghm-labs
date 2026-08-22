package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Critere de recette n5 : broker indisponible, la requete repond quand meme 202 et le lead
 * n'est pas perdu.
 *
 * <p>Le broker est simule en echec plutot qu'arrete pour de vrai : arreter le conteneur
 * casserait aussi la declaration de topologie au demarrage du contexte, et le test
 * mesurerait alors autre chose que ce qu'il pretend mesurer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BrokerIndisponibleTest {

    private static final String SECRET = "secret-de-signature";
    private static final String CORPS =
            "{\"source\":\"formulaire-devis\",\"email\":\"karim@acme.test\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;

    @MockitoBean private RabbitTemplate rabbitTemplate;

    private String clePublique;

    @BeforeEach
    void preparer() {
        rawLeadEventRepository.deleteAll();
        clePublique = "cle-" + UUID.randomUUID();
        Client client = new Client();
        client.setPublicKey(clePublique);
        client.setName("Boutique de test");
        client.setHmacSecret(SECRET);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        clientRepository.saveAndFlush(client);

        willThrow(new AmqpException("broker injoignable"))
                .given(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));
    }

    private static String enTeteValide(String secret, String corps) {
        try {
            long horodatage = Instant.now().getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            String hex = HexFormat.of()
                    .formatHex(mac.doFinal((horodatage + "." + corps).getBytes(UTF_8)));
            return "t=" + horodatage + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void repondQuandMemeAccepteEtGardeLeLeadEnBase() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted());

        // La ligne existe et porte la trace de l'echec : le filet la reprendra, et
        // PendingEventRelayTest prouve qu'il republie bien un FAILED.
        RawLeadEvent evenement = rawLeadEventRepository.findAll().getFirst();
        assertThat(evenement.getStatus()).isEqualTo(RawLeadEventStatus.FAILED);
        assertThat(evenement.getFailureReason()).contains("broker injoignable");
        assertThat(evenement.getPublishedAt()).isNull();
    }
}

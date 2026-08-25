package com.leadflow.tenant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEventRepository;
import java.time.Instant;
import java.util.HexFormat;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Le test qui compte relie l'ecran au pipeline : apres desactivation, le webhook de la
 * boutique doit rendre 401. C'est la preuve que le bouton agit reellement sur la capture,
 * et pas seulement sur une colonne.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class ClientDesactivationTest {

    private static final String SECRET_EN_CLAIR = "secret-hmac-tres-reconnaissable";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;
    @Autowired private RawLeadEventRepository evenementsBruts;

    @AfterEach
    void nettoie() {
        // Les evenements bruts partent en premier : ils referencent client sans cascade,
        // et deux de ces tests envoient un lead pour prouver que la capture a bien change.
        evenementsBruts.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @Test
    void unWebhookDeBoutiqueDesactiveeEstRefuse() throws Exception {
        Client boutique = creeUneBoutique("Boutique a fermer");
        String clePublique = boutique.getPublicKey();

        // Avant : la capture accepte.
        envoieUnLeadSigne(clePublique, SECRET_EN_CLAIR).andExpect(status().isAccepted());

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        // Apres : le meme appel, correctement signe, est refuse.
        envoieUnLeadSigne(clePublique, SECRET_EN_CLAIR).andExpect(status().isUnauthorized());
    }

    @Test
    void laReactivationRestitueLaCapture() throws Exception {
        Client boutique = creeUneBoutique("Boutique a rouvrir");
        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/deactivate")
                .header("Authorization", "Bearer " + jeton()));

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/activate")
                        .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk());

        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isAccepted());
    }

    @Test
    void desactiverUneBoutiqueNeTouchePasAsesCommerciaux() throws Exception {
        Client boutique = creeUneBoutique("Boutique en pause");
        creeUnCommercial(boutique, "actif@test.fr", true);

        mockMvc.perform(post("/api/admin/clients/" + boutique.getId() + "/deactivate")
                .header("Authorization", "Bearer " + jeton()));

        // Coupler les deux ferait perdre l'information de qui etait actif.
        assertThat(commerciaux.countByClientIdAndActiveTrue(boutique.getId())).isEqualTo(1);
    }

    private ResultActions envoieUnLeadSigne(String clePublique, String secret) throws Exception {
        String corps = "{\"source\":\"test\",\"email\":\"prospect@test.fr\"}";
        long horodatage = Instant.now().getEpochSecond();
        String signature = hmacHex(secret, horodatage + "." + corps);
        return mockMvc.perform(post("/api/webhooks/leads/" + clePublique)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Leadflow-Signature", "t=" + horodatage + ",v1=" + signature)
                .content(corps));
    }

    /** Meme forme canonique que HmacSignatureVerifier, copiee ici faute d'acces au package. */
    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Client creeUneBoutique(String nom) {
        Client client = new Client();
        client.setName(nom);
        client.setPublicKey("cle-" + UUID.randomUUID().toString().substring(0, 8));
        client.setHmacSecret(SECRET_EN_CLAIR);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://erp.test", "apiKey", "cle-api"));
        client.setAssignmentStrategy(AssignmentStrategyType.ROUND_ROBIN);
        client.setActive(true);
        return clients.save(client);
    }

    private void creeUnCommercial(Client boutique, String email, boolean actif) {
        SalesRep rep = new SalesRep();
        rep.setClient(boutique);
        rep.setFullName("Commercial " + email);
        rep.setEmail(email);
        rep.setActive(actif);
        commerciaux.save(rep);
    }

    private String jeton() throws Exception {
        String corps = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operateur\",\"password\":\"secret-de-test\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(corps).get("token").asText();
    }
}

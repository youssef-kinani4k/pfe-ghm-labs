package com.leadflow.tenant;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * La rotation ne se verifie pas sur une colonne mais sur le pipeline : apres rotation, une
 * requete webhook signee avec l'ancien secret doit rendre 401, et l'ancienne URL doit etre
 * inconnue. Sans cela on prouverait qu'une valeur a change, pas que la boutique a change de
 * cles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK"})
class RotationDesClesTest {

    private static final String SECRET_EN_CLAIR = "secret-hmac-tres-reconnaissable";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClientRepository clients;
    @Autowired private SalesRepRepository commerciaux;
    @Autowired private RawLeadEventRepository evenementsBruts;

    @AfterEach
    void nettoie() {
        // Les evenements bruts partent en premier : ils referencent client sans cascade, et
        // ces tests envoient des leads pour prouver que les cles ont reellement tourne.
        evenementsBruts.deleteAll();
        commerciaux.deleteAll();
        clients.deleteAll();
    }

    @Test
    void apresRotationLAncienSecretNeSignePlus() throws Exception {
        Client boutique = creeUneBoutique("Boutique a tourner");

        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isAccepted());

        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String nouveau = mapper.readTree(corps).get("hmacSecret").asText();

        // L'ancien secret est mort — c'est toute la raison d'etre du bouton.
        envoieUnLeadSigne(boutique.getPublicKey(), SECRET_EN_CLAIR)
                .andExpect(status().isUnauthorized());
        envoieUnLeadSigne(boutique.getPublicKey(), nouveau)
                .andExpect(status().isAccepted());
    }

    @Test
    void apresRotationDeLaClePubliqueLAncienneUrlEstInconnue() throws Exception {
        Client boutique = creeUneBoutique("Boutique a re-adresser");
        String ancienne = boutique.getPublicKey();

        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-public-key")
                                .header("Authorization", "Bearer " + jeton()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String nouvelle = mapper.readTree(corps).get("publicKey").asText();

        assertThat(nouvelle).isNotEqualTo(ancienne);
        envoieUnLeadSigne(ancienne, SECRET_EN_CLAIR).andExpect(status().isUnauthorized());
        envoieUnLeadSigne(nouvelle, SECRET_EN_CLAIR).andExpect(status().isAccepted());
    }

    @Test
    void leSecretTourneNApparaitDansAucuneAutreReponse() throws Exception {
        Client boutique = creeUneBoutique("Boutique discrete");
        String corps = mockMvc.perform(
                        post("/api/admin/clients/" + boutique.getId() + "/rotate-secret")
                                .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        String nouveau = mapper.readTree(corps).get("hmacSecret").asText();

        String fiche = mockMvc.perform(get("/api/admin/clients/" + boutique.getId())
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();
        String liste = mockMvc.perform(get("/api/admin/clients")
                        .header("Authorization", "Bearer " + jeton()))
                .andReturn().getResponse().getContentAsString();

        assertThat(fiche).doesNotContain(nouveau);
        assertThat(liste).doesNotContain(nouveau);
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

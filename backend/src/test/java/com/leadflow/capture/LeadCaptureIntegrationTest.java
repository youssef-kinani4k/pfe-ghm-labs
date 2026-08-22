package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadCaptureIntegrationTest {

    private static final String SECRET = "secret-de-signature";
    private static final String CORPS =
            "{\"source\":\"formulaire-devis\",\"email\":\"karim@acme.test\"}";
    private static final String CORPS_INVALIDE = "{ceci nest pas du json";

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;

    private String clePublique;
    private UUID clientId;

    @BeforeEach
    void preparerUnClient() {
        rawLeadEventRepository.deleteAll();
        clePublique = "cle-" + UUID.randomUUID();
        Client client = new Client();
        client.setPublicKey(clePublique);
        client.setName("Boutique de test");
        client.setHmacSecret(SECRET);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        clientId = clientRepository.saveAndFlush(client).getId();
    }

    static String signe(String secret, long horodatage, String corps) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            String hex = HexFormat.of()
                    .formatHex(mac.doFinal((horodatage + "." + corps).getBytes(UTF_8)));
            return "t=" + horodatage + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String enTeteValide(String secret, String corps) {
        return signe(secret, Instant.now().getEpochSecond(), corps);
    }

    @Test
    void accepteUneRequeteSigneeEtEcritLEvenementBrut() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        List<RawLeadEvent> evenements = rawLeadEventRepository.findAll();
        assertThat(evenements).hasSize(1);
        RawLeadEvent evenement = evenements.getFirst();
        assertThat(evenement.getClientId()).isEqualTo(clientId);
        assertThat(evenement.getSource()).isEqualTo("formulaire-devis");
        assertThat(evenement.getPayload()).containsEntry("email", "karim@acme.test");
        assertThat(evenement.getSignature()).startsWith("t=");
    }

    @Test
    void refuseUneClePubliqueInconnue() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", "cle-qui-nexiste-pas")
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());

        assertThat(rawLeadEventRepository.findAll()).isEmpty();
    }

    @Test
    void refuseUnClientDesactive() throws Exception {
        Client client = clientRepository.findByPublicKeyAndActiveTrue(clePublique).orElseThrow();
        client.setActive(false);
        clientRepository.saveAndFlush(client);

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());

        assertThat(rawLeadEventRepository.findAll()).isEmpty();
    }

    @Test
    void refuseUneSignatureAbsente() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refuseUneSignatureFausse() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide("mauvais-secret", CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refuseUnHorodatagePerime() throws Exception {
        long vieux = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", signe(SECRET, vieux, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
    }

    /** Corps de la reponse a un refus, pour comparer les causes entre elles. */
    private String corpsDuRefus(String cle, String enTete) throws Exception {
        var requete = post("/api/webhooks/leads/{cle}", cle)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPS);
        if (enTete != null) {
            requete = requete.header("X-Leadflow-Signature", enTete);
        }
        return mockMvc.perform(requete)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void repondLaMemeChoseQuelleQueSoitLaCauseDuRefus() throws Exception {
        // Meme URL, trois causes differentes : les corps doivent etre identiques octet pour
        // octet, sinon la reponse trahit ce qui a echoue.
        long vieux = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();
        String signatureFausse = corpsDuRefus(clePublique, enTeteValide("mauvais-secret", CORPS));

        assertThat(corpsDuRefus(clePublique, signe(SECRET, vieux, CORPS)))
                .isEqualTo(signatureFausse);
        assertThat(corpsDuRefus(clePublique, null)).isEqualTo(signatureFausse);
        assertThat(corpsDuRefus(clePublique, "pas-un-en-tete")).isEqualTo(signatureFausse);
    }

    @Test
    void neTrahitPasQuUneClePubliqueExiste() throws Exception {
        // L'URL differe forcement d'un appel a l'autre puisqu'elle vient de l'appelant :
        // ce qui doit etre indistinguable, c'est le motif et le code, pas le chemin qu'il
        // a lui-meme choisi.
        mockMvc.perform(post("/api/webhooks/leads/{cle}", "cle-inexistante")
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Signature invalide ou expiree"));

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide("mauvais-secret", CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Signature invalide ou expiree"));
    }

    @Test
    void neDeserialisePasLeCorpsAvantDAvoirAuthentifie() throws Exception {
        // JSON invalide ET signature invalide : la reponse doit etre 401 et non 400. C'est
        // la preuve que rien n'est parse avant que l'appelant soit authentifie.
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature",
                                enTeteValide("mauvais-secret", CORPS_INVALIDE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_INVALIDE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refuseUnCorpsIllisibleUneFoisAuthentifie() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS_INVALIDE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS_INVALIDE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUnCorpsSansSource() throws Exception {
        String sansSource = "{\"email\":\"karim@acme.test\"}";

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, sansSource))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sansSource))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejoueLaMemeRequeteSansCreerDeSecondEvenement() throws Exception {
        String enTete = enTeteValide(SECRET, CORPS);

        String premiere = mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTete)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String seconde = mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTete)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(seconde).isEqualTo(premiere);
        assertThat(rawLeadEventRepository.findAll()).hasSize(1);
    }

    @Test
    void deuxSoumissionsDistinctesRestentDeuxEvenements() throws Exception {
        // Horodatages differents donc signatures differentes : ce n'est pas un rejeu, et
        // deux vraies soumissions ne doivent surtout pas etre confondues.
        long maintenant = Instant.now().getEpochSecond();

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", signe(SECRET, maintenant, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", signe(SECRET, maintenant - 1, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted());

        assertThat(rawLeadEventRepository.findAll()).hasSize(2);
    }

    @Test
    void refuseUnCorpsTropGros() throws Exception {
        String enorme = "{\"source\":\"formulaire\",\"message\":\"" + "a".repeat(70000) + "\"}";

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, enorme))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enorme))
                .andExpect(status().isPayloadTooLarge());
    }
}

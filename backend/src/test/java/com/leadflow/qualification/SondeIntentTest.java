package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.config.IntentProperties;
import java.time.Duration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Un diagnostic n'a d'interet que s'il dit quoi reparer.
 *
 * <p>« Cle refusee » couvre trois situations qui demandent trois gestes differents : une cle
 * fausse, une API non activee sur le projet Google, un modele qui n'existe pas pour cette
 * cle. Le message rendu par le fournisseur les distingue, et il ne contient pas la cle —
 * ces tests verrouillent le fait qu'il remonte jusqu'a l'ecran.
 */
class SondeIntentTest {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String MODELE = "gemini-2.5-flash";

    private RestClient.Builder builder;
    private MockRestServiceServer serveur;

    /** Reglage nu : la sonde eprouve la cle qu'on lui passe, pas celle en service. */
    private static final ReglageIntent SANS_REGLAGE = new ReglageIntent() {
        @Override
        public String cleEffective() {
            return null;
        }

        @Override
        public boolean actif() {
            return true;
        }
    };

    @BeforeEach
    void preparation() {
        builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
    }

    private SondeIntent sonde() {
        IntentProperties.Gemini config = new IntentProperties.Gemini(
                true, null, BASE, MODELE, Duration.ofSeconds(3), Duration.ofSeconds(8), 2000);
        return new SondeIntent(config, SANS_REGLAGE, builder);
    }

    private void repond(HttpStatus statut, String message) {
        serveur.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withStatus(statut)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":%d,"message":"%s","status":"INVALID_ARGUMENT"}}
                                """.formatted(statut.value(), message)));
    }

    @Test
    void remonteLeMotifDuFournisseurEtNonLeSeulCodeHttp() {
        repond(HttpStatus.BAD_REQUEST, "API key not valid. Please pass a valid API key.");

        IntentTestResult resultat = sonde().eprouve("cle-fausse");

        assertThat(resultat.ok()).isFalse();
        assertThat(resultat.cause()).isEqualTo(CauseIntent.CLE_REFUSEE);
        // Sans ce message, l'operateur ne sait pas s'il doit refaire la cle ou activer
        // l'API sur son projet.
        assertThat(resultat.detail()).contains("API key not valid");
    }

    @Test
    void distingueUneApiNonActiveeDUneCleFausse() {
        repond(HttpStatus.FORBIDDEN, "Generative Language API has not been used in project 42");

        IntentTestResult resultat = sonde().eprouve("cle-valide-mais-api-fermee");

        assertThat(resultat.cause()).isEqualTo(CauseIntent.CLE_REFUSEE);
        assertThat(resultat.detail()).contains("has not been used in project");
    }

    @Test
    void unModeleInconnuNEstPasUneCleRefusee() {
        repond(HttpStatus.NOT_FOUND, "models/gemini-2.5-flash is not found for API version v1beta");

        IntentTestResult resultat = sonde().eprouve("cle-valide");

        // Refaire la cle ne changerait rien : c'est le modele configure qui est en cause.
        assertThat(resultat.cause()).isEqualTo(CauseIntent.MODELE_INCONNU);
    }

    @Test
    void leQuotaResteDistinctDUneCleRefusee() {
        repond(HttpStatus.TOO_MANY_REQUESTS, "Quota exceeded");

        assertThat(sonde().eprouve("cle-valide").cause()).isEqualTo(CauseIntent.QUOTA_DEPASSE);
    }

    @Test
    void uneCleQuiMarcheRendLIntentionClassee() {
        serveur.expect(requestTo(BASE + MODELE + ":generateContent"))
                .andRespond(withSuccess(
                        """
                        {"candidates":[{"content":{"parts":[{"text":"DEVIS"}]}}]}
                        """,
                        MediaType.APPLICATION_JSON));

        IntentTestResult resultat = sonde().eprouve("cle-valide");

        assertThat(resultat.ok()).isTrue();
        assertThat(resultat.intention()).isEqualTo("DEVIS");
    }

    @Test
    void leDetailNeContientJamaisLaCleEprouvee() {
        repond(HttpStatus.BAD_REQUEST, "API key not valid");

        IntentTestResult resultat = sonde().eprouve("AIzaSyCleQuiNeDoitPasFuir");

        assertThat(resultat.detail()).doesNotContain("AIzaSyCleQuiNeDoitPasFuir");
    }
}

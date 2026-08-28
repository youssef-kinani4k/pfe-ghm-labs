package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.config.IntentProperties;
import java.time.Duration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * La cle et l'interrupteur se lisent a chaque analyse, et non une fois pour toutes au
 * demarrage : c'est ce qui permet a la console de changer le reglage sans redeploiement.
 *
 * <p>Le reglage est un port, pas le service concret : ces tests ne montent ni base ni
 * contexte Spring, et restent a l'etage contractuel comme le reste des tests du connecteur.
 */
class GeminiIntentAnalyzerReglageTest {

    private static final String MESSAGE = "Je veux un devis pour 50 unites";
    private static final String MODELE = "gemini-2.5-flash";
    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private RestClient.Builder builder;
    private MockRestServiceServer serveur;
    private RuleBasedIntentAnalyzer repli;

    /** Reglage mutable : il change entre deux analyses, comme la console le ferait. */
    private static final class ReglageDeTest implements ReglageIntent {
        private String cle;
        private boolean actif = true;

        @Override
        public String cleEffective() {
            return cle;
        }

        @Override
        public boolean actif() {
            return actif;
        }
    }

    private final ReglageDeTest reglage = new ReglageDeTest();

    @BeforeEach
    void preparation() {
        builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        repli = new RuleBasedIntentAnalyzer();
    }

    private GeminiIntentAnalyzer analyseur() {
        IntentProperties.Gemini config = new IntentProperties.Gemini(
                true, null, BASE, MODELE, Duration.ofSeconds(3), Duration.ofSeconds(8), 2000);
        return new GeminiIntentAnalyzer(repli, config, reglage, builder);
    }

    @Test
    void envoieLaCleDuReglageEtNonCelleDeLaConfiguration() {
        reglage.cle = "cle-de-la-console";
        serveur.expect(requestTo(BASE + MODELE + ":generateContent"))
                .andExpect(header("x-goog-api-key", "cle-de-la-console"))
                .andRespond(withSuccess(
                        """
                        {"candidates":[{"content":{"parts":[{"text":"DEVIS"}]}}]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(analyseur().analyse(MESSAGE).source()).isEqualTo(IntentSource.GEMINI);
        serveur.verify();
    }

    @Test
    void unInterrupteurFermeNAppelleMemePasLeModele() {
        reglage.cle = "cle-de-la-console";
        reglage.actif = false;

        IntentAnalysis analyse = analyseur().analyse(MESSAGE);

        assertThat(analyse.source()).isEqualTo(IntentSource.RULES);
        // Aucune attente n'a ete declaree : le moindre appel sortant ferait echouer ceci.
        serveur.verify();
    }

    @Test
    void sansCleLAnalyseResteLexicale() {
        reglage.cle = null;

        assertThat(analyseur().analyse(MESSAGE).source()).isEqualTo(IntentSource.RULES);
        serveur.verify();
    }

    @Test
    void uneCleRetireeEnCoursDeVieFaitBasculerLAnalyseurSuivant() {
        GeminiIntentAnalyzer analyseur = analyseur();
        reglage.cle = "cle-de-la-console";
        serveur.expect(requestTo(Matchers.any(String.class)))
                .andRespond(withSuccess(
                        """
                        {"candidates":[{"content":{"parts":[{"text":"DEVIS"}]}}]}
                        """,
                        MediaType.APPLICATION_JSON));
        assertThat(analyseur.analyse(MESSAGE).source()).isEqualTo(IntentSource.GEMINI);

        reglage.cle = null;

        // Le meme instance, sans redemarrage : la cle est relue a chaque analyse.
        assertThat(analyseur.analyse(MESSAGE).source()).isEqualTo(IntentSource.RULES);
    }
}

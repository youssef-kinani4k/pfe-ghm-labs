package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import com.leadflow.config.IntentProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

/**
 * Etage contractuel, comme pour les adaptateurs ERP de F5 : on asserte le corps envoye et
 * pas seulement le code retour. Un test qui verifie « ca n'a pas plante » ne detecte pas un
 * prompt casse.
 */
class GeminiIntentAnalyzerTest {

    private static final String MESSAGE = "Je veux un devis pour 50 unites";
    private static final String MODELE = "gemini-2.5-flash";
    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private RestClient.Builder builder;
    private MockRestServiceServer serveur;
    private RuleBasedIntentAnalyzer repli;

    private static IntentProperties.Gemini config(String cle) {
        return new IntentProperties.Gemini(
                true, cle, BASE, MODELE, Duration.ofSeconds(3), Duration.ofSeconds(8), 2000);
    }

    private static String reponse(String texte) {
        return """
                {"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}
                """.formatted(texte);
    }

    @BeforeEach
    void preparation() {
        builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        repli = new RuleBasedIntentAnalyzer();
    }

    /** La cle vient desormais du reglage, que la console peut changer a chaud. */
    private GeminiIntentAnalyzer analyseur(String cle) {
        ReglageIntent reglage = new ReglageIntent() {
            @Override
            public String cleEffective() {
                return cle;
            }

            @Override
            public boolean actif() {
                return true;
            }
        };
        return new GeminiIntentAnalyzer(repli, config(cle), reglage, builder);
    }

    @Test
    void appelleLeBonModeleEtEnvoieLeMessageDuProspect() {
        serveur.expect(requestTo(BASE + MODELE + ":generateContent"))
                .andExpect(MockRestRequestMatchers.header("x-goog-api-key", "cle-de-test"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(MESSAGE)))
                .andRespond(withSuccess(reponse("DEVIS"), MediaType.APPLICATION_JSON));

        IntentAnalysis analyse = analyseur("cle-de-test").analyse(MESSAGE);

        assertThat(analyse.intent()).isEqualTo(LeadIntent.DEVIS);
        assertThat(analyse.source()).isEqualTo(IntentSource.GEMINI);
        serveur.verify();
    }

    @Test
    void tolereLesEspacesEtLaCasseDansLaReponse() {
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(reponse("  achat \\n"), MediaType.APPLICATION_JSON));

        assertThat(analyseur("cle-de-test").analyse(MESSAGE).intent())
                .isEqualTo(LeadIntent.ACHAT);
    }

    @Test
    void replieSurLesReglesQuandLeServeurEchoue() {
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());

        IntentAnalysis analyse = analyseur("cle-de-test").analyse(MESSAGE);

        assertThat(analyse.intent()).isEqualTo(LeadIntent.DEVIS);
        assertThat(analyse.source()).isEqualTo(IntentSource.RULES);
    }

    @Test
    void replieSurLesReglesQuandLeQuotaEstDepasse() {
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withTooManyRequests());

        assertThat(analyseur("cle-de-test").analyse(MESSAGE).source())
                .isEqualTo(IntentSource.RULES);
    }

    @Test
    void replieSurLesReglesQuandLaReponseSortDuVocabulaire() {
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(reponse("PEUT-ETRE"), MediaType.APPLICATION_JSON));

        IntentAnalysis analyse = analyseur("cle-de-test").analyse(MESSAGE);

        assertThat(analyse.intent()).isEqualTo(LeadIntent.DEVIS);
        assertThat(analyse.source()).isEqualTo(IntentSource.RULES);
    }

    @Test
    void replieSurLesReglesQuandLeCorpsEstIllisible() {
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess("ceci n'est pas du json", MediaType.APPLICATION_JSON));

        assertThat(analyseur("cle-de-test").analyse(MESSAGE).source())
                .isEqualTo(IntentSource.RULES);
    }

    @Test
    void nAppellePasLeReseauQuandLaCleEstVide() {
        serveur.expect(ExpectedCount.never(), requestTo(org.hamcrest.Matchers.any(String.class)));

        IntentAnalysis analyse = analyseur("  ").analyse(MESSAGE);

        assertThat(analyse.source()).isEqualTo(IntentSource.RULES);
        serveur.verify();
    }

    @Test
    void nAppellePasLeReseauSurUnMessageVide() {
        serveur.expect(ExpectedCount.never(), requestTo(org.hamcrest.Matchers.any(String.class)));

        IntentAnalysis analyse = analyseur("cle-de-test").analyse("   ");

        assertThat(analyse.intent()).isEqualTo(LeadIntent.AUTRE);
        assertThat(analyse.source()).isEqualTo(IntentSource.RULES);
        serveur.verify();
    }

    @Test
    void demandeLaReflexionLaPlusFaibleDansLaFormeAttendueParLeModele() {
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("thinkingLevel")))
                // thinkingBudget etait la forme de gemini-2.5-flash ; les modeles suivants
                // rendent 400 « Request contains an invalid argument » en la recevant, sans
                // rien dire de plus, et l'analyse restait en mode lexical pour toujours.
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("thinkingBudget"))))
                .andRespond(withSuccess(reponse("DEVIS"), MediaType.APPLICATION_JSON));

        analyseur("cle-de-test").analyse(MESSAGE);

        serveur.verify();
    }

    @Test
    void tronqueLeMessageAvantDeLEnvoyer() {
        String tres_long = "a".repeat(5000);
        serveur.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("a".repeat(2001)))))
                .andRespond(withSuccess(reponse("AUTRE"), MediaType.APPLICATION_JSON));

        analyseur("cle-de-test").analyse(tres_long);

        serveur.verify();
    }
}

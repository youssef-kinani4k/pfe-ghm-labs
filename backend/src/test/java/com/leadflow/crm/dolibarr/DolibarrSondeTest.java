package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmCheckCause;
import com.leadflow.crm.model.CrmTarget;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * La sonde est ce que l'ecran de creation appelle avant d'enregistrer : elle doit distinguer
 * les echecs, sinon l'operateur non technique ne saura pas quoi corriger.
 */
class DolibarrSondeTest {

    private static final CrmTarget CIBLE = new CrmTarget(
            "dolibarr", Map.of("baseUrl", "http://erp.test/api/index.php", "apiKey", "cle"));

    private MockRestServiceServer serveur;
    private DolibarrClient client;

    private void prepare() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new DolibarrClient(builder);
    }

    @Test
    void unStatusQuiRepondRendJoignable() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andExpect(header("DOLAPIKEY", "cle"))
                .andRespond(withSuccess(
                        "{\"success\":{\"code\":200,\"dolibarr_version\":\"23.0.2\"}}",
                        MediaType.APPLICATION_JSON));

        CrmCheck resultat = client.verifieAcces(CIBLE);

        assertThat(resultat.ok()).isTrue();
        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.JOIGNABLE);
        serveur.verify();
    }

    @Test
    void unQuatreCentUnRendIdentifiantsRefuses() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.verifieAcces(CIBLE).cause())
                .isEqualTo(CrmCheckCause.IDENTIFIANTS_REFUSES);
    }

    @Test
    void unQuatreCentQuatreRendCibleInconnue() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.verifieAcces(CIBLE).cause()).isEqualTo(CrmCheckCause.CIBLE_INCONNUE);
    }

    @Test
    void uneConnexionRefuseeRendInjoignable() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(withException(new IOException("Connection refused")));

        CrmCheck resultat = client.verifieAcces(CIBLE);

        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.INJOIGNABLE);
        // Le detail technique reste disponible pour qui diagnostique lui-meme.
        assertThat(resultat.detail()).contains("Connection refused");
    }

    @Test
    void unReglageAbsentNAppellePasLErp() {
        prepare();

        CrmCheck resultat = client.verifieAcces(new CrmTarget("dolibarr", Map.of()));

        // Aucune attente posee sur le serveur : la sonde doit rendre la main avant l'appel.
        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.REPONSE_INATTENDUE);
        serveur.verify();
    }

    /**
     * Regression de la recette F7 : Dolibarr renvoie un en-tete {@code WWW-Authenticate}
     * <b>vide</b> sur 401, et {@code HttpURLConnection} levait dessus une
     * {@code IllegalArgumentException} — pas une {@code RestClientException}. L'endpoint de
     * test rendait alors 500 avec une trace la ou l'operateur attendait une phrase.
     *
     * <p>La cause est traitee a la racine dans {@code CrmHttpConfig}, qui n'utilise plus
     * {@code HttpURLConnection}. Ce test verrouille le filet : quoi qu'il arrive sous la
     * pile HTTP, la sonde rend une cause et ne leve pas.
     */
    @Test
    void unEchecHorsRestClientExceptionNeTraverseJamaisLaSonde() {
        prepare();
        serveur.expect(requestTo("http://erp.test/api/index.php/status"))
                .andRespond(request -> {
                    throw new IllegalArgumentException("invalid start or end");
                });

        CrmCheck resultat = client.verifieAcces(CIBLE);

        assertThat(resultat.ok()).isFalse();
        assertThat(resultat.cause()).isEqualTo(CrmCheckCause.REPONSE_INATTENDUE);
        assertThat(resultat.detail()).contains("invalid start or end");
    }
}

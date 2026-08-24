package com.leadflow.crm.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmCheckCause;
import com.leadflow.crm.model.CrmTarget;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdooSondeTest {

    private static final CrmTarget CIBLE = new CrmTarget("odoo", Map.of(
            "baseUrl", "http://odoo.test",
            "database", "leadflow",
            "username", "admin",
            "apiKey", "motdepasse"));

    private MockRestServiceServer serveur;
    private OdooClient client;

    private void prepare() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new OdooClient(builder);
    }

    @Test
    void unUidPositifRendJoignable() {
        prepare();
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess("{\"result\":2}", MediaType.APPLICATION_JSON));

        assertThat(client.verifieAcces(CIBLE).ok()).isTrue();
        serveur.verify();
    }

    @Test
    void unResultatFauxRendIdentifiantsRefuses() {
        prepare();
        // Odoo repond 200 avec « false » quand le mot de passe est mauvais.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess("{\"result\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.verifieAcces(CIBLE).cause())
                .isEqualTo(CrmCheckCause.IDENTIFIANTS_REFUSES);
    }

    @Test
    void uneBaseInexistanteRendCibleInconnue() {
        prepare();
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"error\":{\"data\":{\"message\":\"database \\\"absente\\\" does not"
                                + " exist\"}}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.verifieAcces(CIBLE).cause()).isEqualTo(CrmCheckCause.CIBLE_INCONNUE);
    }

    @Test
    void uneConnexionRefuseeRendInjoignable() {
        prepare();
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withException(new IOException("Connection refused")));

        assertThat(client.verifieAcces(CIBLE).cause()).isEqualTo(CrmCheckCause.INJOIGNABLE);
    }
}

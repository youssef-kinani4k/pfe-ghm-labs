package com.leadflow.crm.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OdooClientTest {

    private static final CrmTarget CIBLE = new CrmTarget("odoo", Map.of(
            "baseUrl", "http://odoo.test",
            "database", "leadflow",
            "username", "admin",
            "apiKey", "cle-odoo"));

    private MockRestServiceServer serveur;
    private OdooClient client;

    @BeforeEach
    void preparer() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new OdooClient(builder);
    }

    @Test
    void authentifieEtRenvoieLUid() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.params.service").value("common"))
                .andExpect(jsonPath("$.params.method").value("authenticate"))
                .andExpect(jsonPath("$.params.args[0]").value("leadflow"))
                .andExpect(jsonPath("$.params.args[1]").value("admin"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":7}", MediaType.APPLICATION_JSON));

        assertThat(client.authentifie(CIBLE)).isEqualTo(7);
        serveur.verify();
    }

    @Test
    void refuseUneAuthentificationSansUid() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":false}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authentifie(CIBLE))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("authentification")
                .hasMessageNotContaining("cle-odoo");
    }

    @Test
    void creeUnEnregistrementEtRenvoieSonIdentifiant() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(jsonPath("$.params.method").value("execute_kw"))
                .andExpect(jsonPath("$.params.args[3]").value("res.partner"))
                .andExpect(jsonPath("$.params.args[4]").value("create"))
                .andExpect(jsonPath("$.params.args[5][0].name").value("Acme"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":31}", MediaType.APPLICATION_JSON));

        assertThat(client.cree(CIBLE, 7, "res.partner", Map.of("name", "Acme"))).isEqualTo("31");
        serveur.verify();
    }

    @Test
    void traiteUnStatut200PorteurDUneErreurCommeUnEchec() {
        // Releve de la sonde : Odoo repond 200, le message exploitable est error.data.message.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"Odoo Server Error\","
                                + "\"data\":{\"message\":\"Invalid field 'champ_inexistant'\"}}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.cree(CIBLE, 7, "res.partner", Map.of("x", "y")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("Invalid field");
    }

    @Test
    void chercheUnUtilisateurParEmailEtRenvoieNulSiAucun() {
        // Releve de la sonde : res.users porte login ET email, d'ou le OU explicite.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(jsonPath("$.params.args[3]").value("res.users"))
                .andExpect(jsonPath("$.params.args[5][0][0]").value("|"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, 7, "inconnu@demo.test")).isNull();
    }

    @Test
    void chercheUnUtilisateurParEmailEtRenvoieSonIdentifiant() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[9]}", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, 7, "amina@demo.test")).isEqualTo("9");
    }

    @Test
    void refuseUneCibleIncomplete() {
        CrmTarget sansBase = new CrmTarget("odoo", Map.of("baseUrl", "http://odoo.test"));

        assertThatThrownBy(() -> client.authentifie(sansBase))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("database");
    }
}

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
    void refuseUneCreationSansIdentifiant() {
        // Reponse tronquee ou proxy intercale : ni result, ni error. Rendre null ferait
        // passer le lead en SYNCED avec une reference vide.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess(
                        "{\"jsonrpc\":\"2.0\",\"id\":1}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.cree(CIBLE, 7, "res.partner", Map.of("name", "Acme")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("res.partner.create");
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
    void ecritEnvoieUnWriteAvecIdentifiantEtChamps() {
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andExpect(method(HttpMethod.POST))
                // Le corps exact, pas seulement le code retour : c'est la forme de l'appel
                // qui casse quand Odoo change, et un 200 ne l'aurait pas vue.
                .andExpect(jsonPath("$.params.method").value("execute_kw"))
                .andExpect(jsonPath("$.params.args[3]").value("crm.lead"))
                .andExpect(jsonPath("$.params.args[4]").value("write"))
                .andExpect(jsonPath("$.params.args[5][0]").value(31))
                .andExpect(jsonPath("$.params.args[6].user_id").value("9"))
                .andRespond(withSuccess("{\"result\": true}", MediaType.APPLICATION_JSON));

        client.ecrit(CIBLE, 2, "crm.lead", "31", Map.of("user_id", "9"));

        serveur.verify();
    }

    @Test
    void ecritRefuseUnResultatQuiNestPasVrai() {
        // Odoo repond 200 meme en cas de refus : l'echec vit dans le corps. Un « false »
        // avale silencieusement ferait croire a une correction qui n'a pas eu lieu.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess("{\"result\": false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ecrit(CIBLE, 2, "crm.lead", "31", Map.of("user_id", "9")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("crm.lead.write");
    }

    @Test
    void ecritRefuseUneReponseSansResultat() {
        // Reponse tronquee ou proxy intercale : ni result, ni error. Meme chemin que le
        // « false » ci-dessus, mais cree() a son propre test dedie a ce cas
        // (refuseUneCreationSansIdentifiant) : l'asymetrie n'a pas de raison d'etre.
        serveur.expect(requestTo("http://odoo.test/jsonrpc"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ecrit(CIBLE, 2, "crm.lead", "31", Map.of("user_id", "9")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("crm.lead.write");
    }

    @Test
    void ecritRefuseUnIdentifiantIllisibleSansAppelHttp() {
        // Aucun serveur.expect(...) n'est pose : le controle du format de l'identifiant se
        // fait avant l'envoi. Si ecrit(...) tentait malgre tout une requete, elle serait
        // rejetee comme non attendue par MockRestServiceServer, et l'assertion ci-dessous
        // echouerait sur le mauvais type d'exception plutot que de passer silencieusement.
        assertThatThrownBy(() -> client.ecrit(CIBLE, 2, "crm.lead", "abc", Map.of("user_id", "9")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("abc");

        serveur.verify();
    }

    @Test
    void refuseUneCibleIncomplete() {
        CrmTarget sansBase = new CrmTarget("odoo", Map.of("baseUrl", "http://odoo.test"));

        assertThatThrownBy(() -> client.authentifie(sansBase))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("database");
    }
}

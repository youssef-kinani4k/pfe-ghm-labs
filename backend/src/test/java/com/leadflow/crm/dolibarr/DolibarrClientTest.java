package com.leadflow.crm.dolibarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmTarget;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DolibarrClientTest {

    private static final CrmTarget CIBLE = new CrmTarget(
            "dolibarr",
            Map.of("baseUrl", "http://erp.test/api/index.php", "apiKey", "cle-de-test"));

    private MockRestServiceServer serveur;
    private DolibarrClient client;

    @BeforeEach
    void preparer() {
        RestClient.Builder builder = RestClient.builder();
        serveur = MockRestServiceServer.bindTo(builder).build();
        client = new DolibarrClient(builder);
    }

    @Test
    void creeUnTiersEtRenvoieSonIdentifiant() {
        serveur.expect(requestTo("http://erp.test/api/index.php/thirdparties"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("DOLAPIKEY", "cle-de-test"))
                .andExpect(jsonPath("$.name").value("Acme"))
                .andRespond(withSuccess("42", MediaType.APPLICATION_JSON));

        String reference = client.creeTiers(CIBLE, Map.of("name", "Acme"));

        assertThat(reference).isEqualTo("42");
        serveur.verify();
    }

    @Test
    void chercheUnUtilisateurParEmail() {
        serveur.expect(requestTo(Matchers.containsString("/users")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("DOLAPIKEY", "cle-de-test"))
                .andRespond(withSuccess(
                        "[{\"id\":\"9\",\"email\":\"amina@demo.test\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, "amina@demo.test")).isEqualTo("9");
        serveur.verify();
    }

    @Test
    void renvoieNulQuandAucunUtilisateurNeCorrespond() {
        serveur.expect(requestTo(Matchers.containsString("/users")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.chercheUtilisateurParEmail(CIBLE, "inconnu@demo.test")).isNull();
    }

    @Test
    void traduitUneErreurHttpEnCrmSyncExceptionSansDivulguerLaCle() {
        serveur.expect(requestTo("http://erp.test/api/index.php/thirdparties"))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"champ manquant\"}}"));

        assertThatThrownBy(() -> client.creeTiers(CIBLE, Map.of("name", "Acme")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("thirdparties")
                // Le corps de la reponse est la seule information exploitable dans la trace.
                .hasMessageContaining("champ manquant")
                .hasMessageNotContaining("cle-de-test");
    }

    @Test
    void refuseUneCibleSansUrlNiCle() {
        CrmTarget incomplete = new CrmTarget("dolibarr", Map.of("baseUrl", "http://erp.test"));

        assertThatThrownBy(() -> client.creeTiers(incomplete, Map.of("name", "Acme")))
                .isInstanceOf(CrmSyncException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void lieLeResponsableParUnAppelDedie() {
        // Releve de la sonde : fk_user_resp est ignore a la creation comme en PUT.
        serveur.expect(requestTo(Matchers.containsString("/projects/99/contacts")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(requestTo(Matchers.containsString("fk_socpeople=9")))
                .andExpect(requestTo(Matchers.containsString("PROJECTLEADER")))
                .andRespond(withSuccess("{\"id\":\"99\"}", MediaType.APPLICATION_JSON));

        client.lieResponsable(CIBLE, "99", "9");

        serveur.verify();
    }

    @Test
    void envoieUnCorpsJson() {
        serveur.expect(requestTo("http://erp.test/api/index.php/contacts"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.socid").value("42"))
                .andRespond(withSuccess("77", MediaType.APPLICATION_JSON));

        assertThat(client.creeContact(CIBLE, Map.of("socid", "42"))).isEqualTo("77");
    }
}

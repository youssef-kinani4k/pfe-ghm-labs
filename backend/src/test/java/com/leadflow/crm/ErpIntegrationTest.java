package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.crm.dolibarr.DolibarrClient;
import com.leadflow.crm.dolibarr.DolibarrConnector;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.crm.odoo.OdooClient;
import com.leadflow.crm.odoo.OdooConnector;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Etage 2 de la strategie de test : contre de vrais conteneurs Dolibarr et Odoo.
 *
 * <p>Exclu de la suite par defaut ({@code @Tag("erp")}). Pour le lancer :
 * <pre>
 * docker compose --profile dolibarr --profile odoo up -d
 * # puis la procedure de docs/erp-integration-setup.md
 * export LEADFLOW_DOLIBARR_API_KEY=...
 * ./mvnw verify -Perp-it
 * </pre>
 */
@Tag("erp")
class ErpIntegrationTest {

    private static CrmTarget cibleDolibarr() {
        return new CrmTarget("dolibarr", Map.of(
                "baseUrl", System.getenv().getOrDefault(
                        "LEADFLOW_DOLIBARR_URL", "http://localhost:8081/api/index.php"),
                "apiKey", System.getenv().getOrDefault("LEADFLOW_DOLIBARR_API_KEY", "")));
    }

    private static CrmTarget cibleOdoo() {
        return new CrmTarget("odoo", Map.of(
                "baseUrl", System.getenv().getOrDefault("LEADFLOW_ODOO_URL", "http://localhost:8069"),
                "database", System.getenv().getOrDefault("LEADFLOW_ODOO_DB", "leadflow"),
                "username", System.getenv().getOrDefault("LEADFLOW_ODOO_USER", "admin"),
                "apiKey", System.getenv().getOrDefault("LEADFLOW_ODOO_PASSWORD", "admin")));
    }

    private static CrmLead leadUnique() {
        String marque = UUID.randomUUID().toString().substring(0, 8);
        return new CrmLead("LF-" + marque.toUpperCase(Locale.ROOT), "Acme " + marque,
                "Amina", "Bensalem",
                "amina+" + marque + "@exemple.test", "+212600000000",
                "Je veux un devis pour 50 unites", "DEMANDE_DEVIS", 72, "MA", "industrie", null);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEADFLOW_DOLIBARR_API_KEY", matches = ".+")
    void dolibarrCreeLesTroisObjetsPuisNeLesRecreePasAuRejeu() {
        DolibarrConnector connecteur =
                new DolibarrConnector(new DolibarrClient(RestClient.builder()));
        CrmLead lead = leadUnique();

        CrmSyncResult premier = connecteur.sync(lead, cibleDolibarr(), CrmSyncState.VIERGE);

        assertThat(premier.accountRef()).isNotBlank();
        assertThat(premier.contactRef()).isNotBlank();
        assertThat(premier.opportunityRef()).isNotBlank();

        // Rejeu partiel : seul le tiers est connu. C'est le seul scenario qu'un vrai ERP
        // peut departager — avec l'etat complet, l'adaptateur sort sans le moindre appel et
        // l'assertion serait vraie sans conteneur. Ici Dolibarr doit accepter le socid
        // reutilise et rendre un contact et une opportunite neufs.
        CrmSyncResult rejeu = connecteur.sync(lead, cibleDolibarr(),
                new CrmSyncState(premier.accountRef(), null, null, null));

        assertThat(rejeu.accountRef()).isEqualTo(premier.accountRef());
        assertThat(rejeu.contactRef()).isNotBlank().isNotEqualTo(premier.contactRef());
        assertThat(rejeu.opportunityRef()).isNotBlank().isNotEqualTo(premier.opportunityRef());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEADFLOW_ODOO_DB", matches = ".+")
    void odooCreeLesTroisObjetsPuisNeLesRecreePasAuRejeu() {
        OdooConnector connecteur = new OdooConnector(new OdooClient(RestClient.builder()));
        CrmLead lead = leadUnique();

        CrmSyncResult premier = connecteur.sync(lead, cibleOdoo(), CrmSyncState.VIERGE);

        assertThat(premier.accountRef()).isNotBlank();
        assertThat(premier.contactRef()).isNotBlank();
        assertThat(premier.opportunityRef()).isNotBlank();

        // Meme raisonnement que pour Dolibarr : le rejeu part de la seule societe connue, et
        // Odoo doit accepter le parent_id d'une res.partner existante.
        CrmSyncResult rejeu = connecteur.sync(lead, cibleOdoo(),
                new CrmSyncState(premier.accountRef(), null, null, null));

        assertThat(rejeu.accountRef()).isEqualTo(premier.accountRef());
        assertThat(rejeu.contactRef()).isNotBlank().isNotEqualTo(premier.contactRef());
        assertThat(rejeu.opportunityRef()).isNotBlank().isNotEqualTo(premier.opportunityRef());
    }
}

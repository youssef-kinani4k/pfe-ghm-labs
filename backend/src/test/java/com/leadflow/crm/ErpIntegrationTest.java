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
import java.util.List;
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

    /**
     * Eprouve la promesse entiere de la propagation cote Dolibarr : apres une reaffectation,
     * l'opportunite doit porter le <strong>nouveau</strong> responsable et lui seul.
     *
     * <p>Ce test lit les contacts du projet par un appel direct, sans passer par
     * {@link DolibarrClient} qui n'expose aucune lecture de ce genre. C est delibere : la
     * recette de F15 a montre qu'un test qui se contente de verifier l'absence d'exception ne
     * voit pas un ancien responsable reste en place, et c'est precisement le defaut qui avait
     * echappe a tout le monde.
     *
     * <p>Il couvre aussi les deux idempotences, qui ne sont pas de meme nature : la pose d'un
     * lien deja present rend un {@code 500} que {@code lieResponsable} absorbe, tandis que le
     * retrait d'un lien deja absent rend {@code 200} sans rien faire.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "LEADFLOW_DOLIBARR_API_KEY", matches = ".+")
    void dolibarrReaffecteChangeLeResponsableDeLOpportunite() {
        DolibarrConnector connecteur =
                new DolibarrConnector(new DolibarrClient(RestClient.builder()));
        CrmTarget cible = cibleDolibarr();
        CrmLead lead = leadUnique();
        CrmSyncResult synchronise = connecteur.sync(
                new CrmLead(lead.reference(), lead.companyName(), lead.firstName(),
                        lead.lastName(), lead.email(), lead.phone(), lead.message(),
                        lead.detectedIntent(), lead.score(), lead.countryCode(),
                        lead.sector(), "1"),
                cible, CrmSyncState.VIERGE);
        String projet = synchronise.opportunityRef();
        assertThat(projet).isNotBlank();
        assertThat(chefsDeProjet(projet)).containsExactly("1");

        connecteur.reaffecte(new CrmSyncState(null, null, projet, "1"), "2", cible);

        assertThat(chefsDeProjet(projet)).containsExactly("2");

        // Rejeu de la meme reaffectation : la livraison etant at-least-once, il est attendu.
        // La pose heurte le doublon absorbe par lieResponsable, et le retrait porte sur un
        // lien deja absent. L'etat final doit etre le meme.
        connecteur.reaffecte(new CrmSyncState(null, null, projet, "1"), "2", cible);

        assertThat(chefsDeProjet(projet)).containsExactly("2");
    }

    /** @return les identifiants utilisateur lies au projet comme chefs de projet. */
    @SuppressWarnings("unchecked")
    private static List<String> chefsDeProjet(String projet) {
        CrmTarget cible = cibleDolibarr();
        List<Map<String, Object>> contacts = RestClient.builder()
                .baseUrl(cible.settings().get("baseUrl"))
                .defaultHeader("DOLAPIKEY", cible.settings().get("apiKey"))
                .build()
                .get()
                .uri("/projects/" + projet + "/contacts")
                .retrieve()
                .body(List.class);
        return contacts == null ? List.of() : contacts.stream()
                .filter(contact -> "PROJECTLEADER".equals(String.valueOf(contact.get("code"))))
                .map(contact -> String.valueOf(contact.get("id")))
                .toList();
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

    /**
     * Cote Odoo, {@code reaffecte} n'a pas d'equivalent de la fenetre residuelle documentee
     * pour Dolibarr : {@code write} sur {@code user_id} est idempotent, un rejeu avec le
     * meme utilisateur ne fait qu'ecrire de nouveau la meme valeur. {@code OdooClient}
     * n'expose aucune lecture : la seule chose verifiable depuis ce module est l'absence
     * d'exception sur les deux appels.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "LEADFLOW_ODOO_DB", matches = ".+")
    void odooReaffecteChangeLeResponsableDeLOpportunite() {
        OdooConnector connecteur = new OdooConnector(new OdooClient(RestClient.builder()));
        CrmTarget cible = cibleOdoo();
        CrmSyncResult synchronise = connecteur.sync(leadUnique(), cible, CrmSyncState.VIERGE);
        assertThat(synchronise.opportunityRef()).isNotBlank();

        connecteur.reaffecte(
                new CrmSyncState(null, null, synchronise.opportunityRef(), null), "2", cible);
        connecteur.reaffecte(
                new CrmSyncState(null, null, synchronise.opportunityRef(), null), "2", cible);
    }
}

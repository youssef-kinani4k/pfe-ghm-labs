package com.leadflow.crm.odoo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncException;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OdooConnectorTest {

    private static final CrmTarget CIBLE = new CrmTarget("odoo", Map.of(
            "baseUrl", "http://odoo.test",
            "database", "leadflow",
            "username", "admin",
            "apiKey", "cle-odoo"));

    /** Faux transport : ce test porte sur la traduction, pas sur le JSON-RPC. */
    private static final class TransportFactice extends OdooClient {

        private final List<String> appels = new ArrayList<>();
        private final Map<String, Map<String, Object>> corpsParModele = new LinkedHashMap<>();
        private boolean echoueSurOpportunite;
        private int authentifications;

        private TransportFactice() {
            super(RestClient.builder());
        }

        @Override
        public int authentifie(CrmTarget target) {
            authentifications++;
            return 7;
        }

        @Override
        public String cree(CrmTarget target, int uid, String modele, Map<String, Object> champs) {
            corpsParModele.put(modele + "#" + appels.size(), champs);
            appels.add(modele);
            if ("crm.lead".equals(modele)) {
                if (echoueSurOpportunite) {
                    throw new CrmSyncException("odoo", "champ refuse", null);
                }
                return "99";
            }
            return Boolean.TRUE.equals(champs.get("is_company")) ? "31" : "32";
        }

        @Override
        public String chercheUtilisateurParEmail(CrmTarget target, int uid, String email) {
            appels.add("res.users");
            return "9";
        }
    }

    private static CrmLead lead(String companyName) {
        return new CrmLead("LF-000000000001", companyName, "Amina", "Bensalem",
                "amina@acme.test", "+212600000000",
                "Je veux un devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "9");
    }

    private final TransportFactice transport = new TransportFactice();

    private final OdooConnector connecteur = new OdooConnector(transport);

    @Test
    void seDeclareSousLIdentifiantOdoo() {
        assertThat(connecteur.providerId()).isEqualTo("odoo");
    }

    @Test
    void creeLaSocietePuisLeContactPuisLOpportunite() {
        CrmSyncResult resultat = connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).containsExactly("res.partner", "res.partner", "crm.lead");
        assertThat(resultat.accountRef()).isEqualTo("31");
        assertThat(resultat.contactRef()).isEqualTo("32");
        assertThat(resultat.opportunityRef()).isEqualTo("99");
        assertThat(transport.authentifications).isEqualTo(1);
    }

    @Test
    void rattacheLeContactALaSocieteEtLOpportuniteAuPartenaire() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsParModele.get("res.partner#0")).containsEntry("is_company", true);
        assertThat(transport.corpsParModele.get("res.partner#1")).containsEntry("parent_id", "31");
        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("partner_id", "31");
        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("type", "opportunity");
        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("user_id", "9");
    }

    @Test
    void traduitLeScoreEnPriorite() {
        connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.corpsParModele.get("crm.lead#2")).containsEntry("priority", "2");
    }

    @Test
    void creeUnContactSansSocieteQuandAucuneNEstFournie() {
        CrmSyncResult resultat = connecteur.sync(lead(null), CIBLE, CrmSyncState.VIERGE);

        assertThat(transport.appels).containsExactly("res.partner", "crm.lead");
        assertThat(resultat.accountRef()).isNull();
        assertThat(resultat.contactRef()).isEqualTo("32");
    }

    @Test
    void neRecreeRienDeCeQuiExisteDeja() {
        CrmSyncResult resultat =
                connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("31", "32", null));

        assertThat(transport.appels).containsExactly("crm.lead");
        assertThat(resultat.accountRef()).isEqualTo("31");
    }

    @Test
    void neSAuthentifieMemePasQuandToutExisteDeja() {
        connecteur.sync(lead("Acme"), CIBLE, new CrmSyncState("31", "32", "99"));

        assertThat(transport.appels).isEmpty();
        assertThat(transport.authentifications).isZero();
    }

    @Test
    void enrichitLExceptionDeCeQuiAvaitDejaEteCree() {
        transport.echoueSurOpportunite = true;

        assertThatThrownBy(() -> connecteur.sync(lead("Acme"), CIBLE, CrmSyncState.VIERGE))
                .isInstanceOf(CrmSyncException.class)
                .satisfies(echec -> {
                    CrmSyncState partiel = ((CrmSyncException) echec).partialState();
                    assertThat(partiel.accountRef()).isEqualTo("31");
                    assertThat(partiel.contactRef()).isEqualTo("32");
                    assertThat(partiel.opportunityRef()).isNull();
                });
    }

    @Test
    void resoutLeCommercialParSonEmail() {
        assertThat(connecteur.resolveAssignee(
                new CrmAssignee("Amina Bensalem", "amina@demo.test"), CIBLE)).isEqualTo("9");
    }
}

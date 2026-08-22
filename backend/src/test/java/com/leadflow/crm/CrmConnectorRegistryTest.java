package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.CrmProperties;
import com.leadflow.crm.model.CrmAssignee;
import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmSyncState;
import com.leadflow.crm.model.CrmTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrmConnectorRegistryTest {

    /** Connecteur factice : le registre ne doit dependre d'aucun adaptateur reel. */
    private static final class ConnecteurFactice implements CrmConnector {

        private final String providerId;

        private CrmLead leadRecu;
        private CrmTarget cibleRecue;
        private CrmSyncState etatRecu;

        private ConnecteurFactice(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous) {
            this.leadRecu = lead;
            this.cibleRecue = target;
            this.etatRecu = previous;
            return new CrmSyncResult(providerId, "1", "2", "3", null, Instant.now());
        }

        @Override
        public String resolveAssignee(CrmAssignee assignee, CrmTarget target) {
            return "u-" + assignee.email();
        }
    }

    private static CrmProperties proprietes(boolean odooActive) {
        CrmProperties.Provider actif =
                new CrmProperties.Provider(true, Duration.ofSeconds(5), Duration.ofSeconds(15));
        CrmProperties.Provider odoo =
                new CrmProperties.Provider(odooActive, Duration.ofSeconds(5), Duration.ofSeconds(15));
        return new CrmProperties(Map.of("dolibarr", actif, "odoo", odoo));
    }

    private final ConnecteurFactice dolibarr = new ConnecteurFactice("dolibarr");

    private final ConnecteurFactice odoo = new ConnecteurFactice("odoo");

    private final CrmConnectorRegistry registry =
            new CrmConnectorRegistry(List.of(dolibarr, odoo), proprietes(true));

    @Test
    void resoutUnConnecteurParSonIdentifiant() {
        assertThat(registry.forProvider("odoo")).isSameAs(odoo);
    }

    @Test
    void listeLesFournisseursActives() {
        CrmConnectorRegistry avecOdooDesactive =
                new CrmConnectorRegistry(List.of(dolibarr, odoo), proprietes(false));

        assertThat(avecOdooDesactive.availableProviders()).containsExactly("dolibarr");
    }

    @Test
    void refuseUnFournisseurInconnuEnNommantLesDisponibles() {
        assertThatThrownBy(() -> registry.forProvider("salesforce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salesforce")
                .hasMessageContaining("dolibarr");
    }

    @Test
    void refuseUnFournisseurDesactiveAvecUnMessageDistinct() {
        CrmConnectorRegistry avecOdooDesactive =
                new CrmConnectorRegistry(List.of(dolibarr, odoo), proprietes(false));

        assertThatThrownBy(() -> avecOdooDesactive.forProvider("odoo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desactive")
                .hasMessageContaining("leadflow.crm.providers.odoo.enabled");
    }

    @Test
    void refuseUnIdentifiantNulSansLeverDeNullPointerException() {
        assertThatThrownBy(() -> registry.forProvider(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void transmetLeLeadLaCibleEtLEtatAnterieurAuConnecteur() {
        CrmTarget cible = new CrmTarget("dolibarr", Map.of("baseUrl", "http://client-a:8081"));
        CrmLead lead = new CrmLead("LF-000000000001", "Acme", "Amina", "Bensalem", "amina@exemple.test",
                "+212600000000", "Demande de devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "7");
        CrmSyncState etat = new CrmSyncState("42", null, null);

        registry.forProvider("dolibarr").sync(lead, cible, etat);

        assertThat(dolibarr.leadRecu).isSameAs(lead);
        assertThat(dolibarr.cibleRecue).isSameAs(cible);
        assertThat(dolibarr.etatRecu).isSameAs(etat);
        assertThat(odoo.cibleRecue).isNull();
    }
}

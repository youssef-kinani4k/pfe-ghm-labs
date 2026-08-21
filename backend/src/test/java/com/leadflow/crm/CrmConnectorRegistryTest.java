package com.leadflow.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.crm.model.CrmLead;
import com.leadflow.crm.model.CrmSyncResult;
import com.leadflow.crm.model.CrmTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrmConnectorRegistryTest {

    /** Connecteur factice : le registre ne doit dependre d'aucun adaptateur reel. */
    private static final class ConnecteurFactice implements CrmConnector {

        private final String providerId;

        /** Derniers arguments recus par {@link #sync} : ce que le registre a reellement transmis. */
        private CrmLead leadRecu;

        private CrmTarget cibleRecue;

        private ConnecteurFactice(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target) {
            this.leadRecu = lead;
            this.cibleRecue = target;
            return new CrmSyncResult(providerId, "1", "2", "3", "4", Instant.now());
        }
    }

    private final ConnecteurFactice dolibarr = new ConnecteurFactice("dolibarr");

    private final ConnecteurFactice odoo = new ConnecteurFactice("odoo");

    private final CrmConnectorRegistry registry = new CrmConnectorRegistry(List.of(dolibarr, odoo));

    @Test
    void resoutUnConnecteurParSonIdentifiant() {
        assertThat(registry.forProvider("odoo").providerId()).isEqualTo("odoo");
    }

    @Test
    void listeLesFournisseursDisponibles() {
        assertThat(registry.availableProviders()).containsExactlyInAnyOrder("dolibarr", "odoo");
    }

    @Test
    void refuseUnFournisseurInconnuEnNommantLesDisponibles() {
        assertThatThrownBy(() -> registry.forProvider("salesforce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salesforce")
                .hasMessageContaining("dolibarr");
    }

    @Test
    void transmetLaCibleAuConnecteur() {
        CrmTarget cible = new CrmTarget("dolibarr", Map.of("baseUrl", "http://client-a:8081"));
        CrmLead lead = new CrmLead("Acme", "Amina", "Bensalem", "amina@exemple.test",
                "+212600000000", "Demande de devis", "DEMANDE_DEVIS", 72, "MA", "industrie", "7");

        registry.forProvider("dolibarr").sync(lead, cible);

        assertThat(dolibarr.cibleRecue).isSameAs(cible);
        assertThat(dolibarr.leadRecu).isSameAs(lead);
        assertThat(odoo.cibleRecue).isNull();
    }
}

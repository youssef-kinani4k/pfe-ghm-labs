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

        private ConnecteurFactice(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public CrmSyncResult sync(CrmLead lead, CrmTarget target) {
            return new CrmSyncResult(providerId, "1", "2", "3", "4", Instant.now());
        }
    }

    private final CrmConnectorRegistry registry = new CrmConnectorRegistry(
            List.of(new ConnecteurFactice("dolibarr"), new ConnecteurFactice("odoo")));

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

        CrmSyncResult resultat = registry.forProvider("dolibarr").sync(lead, cible);

        assertThat(resultat.providerId()).isEqualTo("dolibarr");
    }
}

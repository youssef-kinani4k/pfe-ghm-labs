package com.leadflow.common.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.config.DashboardProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sans Spring : le garde-fou vit dans le constructeur, donc il s'eprouve en appelant le
 * constructeur.
 *
 * <p>Pourquoi un echec au demarrage plutot qu'a la connexion : un hash vide laissait
 * l'application demarrer et faisait echouer la premiere connexion sans rien dire de la
 * cause. Un operateur qui decouvre le probleme a l'ecran de connexion n'a aucun moyen de
 * le diagnostiquer. Meme regle que LEADFLOW_MASTER_KEY depuis F1 et LEADFLOW_JWT_SECRET
 * depuis F6 : refuser de demarrer plutot que d'echouer plus tard, ailleurs.
 */
class DashboardUserDetailsServiceTest {

    private static final String HASH =
            "$2a$10$k1ZYaZoOllGK2VFIAEZt9uWK6qqFReloDQRq3MbCQFPFoBmxXpYKK";

    private static DashboardProperties avec(List<DashboardProperties.Compte> comptes) {
        return new DashboardProperties("secret-de-signature-de-test-32-octets-ok",
                Duration.ofHours(8), comptes);
    }

    @Test
    void refuseDeDemarrerQuandLeHashEstVide() {
        assertThatThrownBy(() -> new DashboardUserDetailsService(
                avec(List.of(new DashboardProperties.Compte("admin", "")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEADFLOW_ADMIN_PASSWORD_HASH");
    }

    @Test
    void refuseDeDemarrerQuandAucunCompteNEstDeclare() {
        assertThatThrownBy(() -> new DashboardUserDetailsService(avec(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEADFLOW_ADMIN_PASSWORD_HASH");
    }

    @Test
    void demarreDesQuUnCompteAUnHash() {
        assertThatCode(() -> new DashboardUserDetailsService(
                avec(List.of(new DashboardProperties.Compte("admin", HASH)))))
                .doesNotThrowAnyException();
    }
}

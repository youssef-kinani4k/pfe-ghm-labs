package com.leadflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AnalyticsPropertiesTest {

    @Test
    void leFuseauParDefautEstCeluiDeLAgence() {
        // Un fuseau absent ne doit pas retomber sur UTC : un lead recu a 00 h 30 heure
        // locale tomberait dans la journee de la veille sur le graphique, decalage
        // silencieux que personne ne rattache jamais a sa cause.
        assertThat(new AnalyticsProperties(null).fuseau()).isEqualTo("Europe/Paris");
        assertThat(new AnalyticsProperties("  ").fuseau()).isEqualTo("Europe/Paris");
    }

    @Test
    void leFuseauSurchargeEstRespecte() {
        AnalyticsProperties props = new AnalyticsProperties("Africa/Casablanca");

        assertThat(props.fuseau()).isEqualTo("Africa/Casablanca");
        assertThat(props.zone()).isEqualTo(ZoneId.of("Africa/Casablanca"));
    }

    @Test
    void unFuseauInconnuEchoueAuDemarrageEtNonALaPremiereRequete() {
        // La validation a lieu dans le constructeur compact et fait echouer le demarrage.
        // La reporter a la premiere requete ferait echouer le premier chargement de l'ecran,
        // plusieurs jours apres le deploiement fautif.
        assertThatThrownBy(() -> new AnalyticsProperties("Mars/Olympus"))
                .isInstanceOf(java.time.DateTimeException.class)
                .hasMessageContaining("Mars/Olympus");
    }
}

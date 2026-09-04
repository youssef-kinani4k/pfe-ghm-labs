package com.leadflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationPropertiesTest {

    private static NotificationProperties.Smtp smtp(boolean enabled, String host) {
        return new NotificationProperties.Smtp(
                enabled, host, 587, "u", "p", "leadflow@agence.test", Duration.ofSeconds(10));
    }

    @Test
    void unHoteAbsentRendLeCanalNonConfigure() {
        assertThat(new NotificationProperties(smtp(true, null)).smtp().configure())
                .isFalse();
        assertThat(new NotificationProperties(smtp(true, "  ")).smtp().configure())
                .isFalse();
    }

    @Test
    void unCanalEteintNestPasConfigureMemeAvecUnHote() {
        // Le drapeau permet d'eteindre l'envoi sans effacer les reglages, par exemple pour
        // une demonstration : sans lui, il faudrait vider l'hote puis le retrouver.
        assertThat(new NotificationProperties(smtp(false, "smtp.test")).smtp().configure())
                .isFalse();
    }

    @Test
    void unHotePresentEtLeCanalAllumeSontConfigures() {
        assertThat(new NotificationProperties(smtp(true, "smtp.test")).smtp().configure())
                .isTrue();
    }
}

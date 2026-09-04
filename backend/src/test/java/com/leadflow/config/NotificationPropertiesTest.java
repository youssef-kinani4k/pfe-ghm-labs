package com.leadflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationPropertiesTest {

    private static final String URL = "http://localhost:4200/leads/{id}";

    private static NotificationProperties.Smtp smtp(boolean enabled, String host) {
        return new NotificationProperties.Smtp(
                enabled, host, 587, "u", "p", "leadflow@agence.test", Duration.ofSeconds(10));
    }

    @Test
    void unHoteAbsentRendLeCanalNonConfigure() {
        assertThat(new NotificationProperties(smtp(true, null), URL).smtp().configure())
                .isFalse();
        assertThat(new NotificationProperties(smtp(true, "  "), URL).smtp().configure())
                .isFalse();
    }

    @Test
    void unCanalEteintNestPasConfigureMemeAvecUnHote() {
        // Le drapeau permet d'eteindre l'envoi sans effacer les reglages, par exemple pour
        // une demonstration : sans lui, il faudrait vider l'hote puis le retrouver.
        assertThat(new NotificationProperties(smtp(false, "smtp.test"), URL).smtp().configure())
                .isFalse();
    }

    @Test
    void unHotePresentEtLeCanalAllumeSontConfigures() {
        assertThat(new NotificationProperties(smtp(true, "smtp.test"), URL).smtp().configure())
                .isTrue();
    }

    @Test
    void lUrlDeFicheRemplaceLIdentifiantDuLead() {
        UUID leadId = UUID.fromString("0198f3c2-0000-7000-8000-000000000042");

        assertThat(new NotificationProperties(smtp(true, "smtp.test"), URL).urlDe(leadId))
                .isEqualTo("http://localhost:4200/leads/" + leadId);
    }
}

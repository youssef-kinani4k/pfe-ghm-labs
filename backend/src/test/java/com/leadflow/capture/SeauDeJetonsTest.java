package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SeauDeJetonsTest {

    private static final long UNE_SECONDE = Duration.ofSeconds(1).toNanos();

    /** Un seau plein absorbe exactement sa rafale, puis refuse. */
    @Test
    void absorbeLaRafalePuisRefuse() {
        SeauDeJetons seau = new SeauDeJetons(3, 1.0, 0L);

        assertThat(seau.consomme(0L)).isTrue();
        assertThat(seau.consomme(0L)).isTrue();
        assertThat(seau.consomme(0L)).isTrue();
        assertThat(seau.consomme(0L)).isFalse();
    }

    /** Le temps qui passe recharge, a raison de jetonsParSeconde. */
    @Test
    void rechargeAvecLeTemps() {
        SeauDeJetons seau = new SeauDeJetons(3, 1.0, 0L);
        seau.consomme(0L);
        seau.consomme(0L);
        seau.consomme(0L);

        assertThat(seau.consomme(UNE_SECONDE)).isTrue();
        assertThat(seau.consomme(UNE_SECONDE)).isFalse();
    }

    /** La recharge ne depasse jamais la capacite : une longue inactivite ne cree pas de credit. */
    @Test
    void neDepassePasLaCapacite() {
        SeauDeJetons seau = new SeauDeJetons(2, 1.0, 0L);

        long uneHeure = Duration.ofHours(1).toNanos();
        assertThat(seau.consomme(uneHeure)).isTrue();
        assertThat(seau.consomme(uneHeure)).isTrue();
        assertThat(seau.consomme(uneHeure)).isFalse();
    }

    /** Retry-After : l'attente annoncee est d'au moins une seconde, jamais zero. */
    @Test
    void annonceUneAttenteExploitable() {
        SeauDeJetons seau = new SeauDeJetons(1, 0.5, 0L);
        seau.consomme(0L);

        assertThat(seau.attenteSecondes(0L)).isEqualTo(2L);
    }

    /** Le dernier acces sert a l'eviction des seaux inactifs. */
    @Test
    void retientSonDernierAcces() {
        SeauDeJetons seau = new SeauDeJetons(2, 1.0, 0L);
        seau.consomme(5 * UNE_SECONDE);

        assertThat(seau.dernierAcces()).isEqualTo(5 * UNE_SECONDE);
    }
}

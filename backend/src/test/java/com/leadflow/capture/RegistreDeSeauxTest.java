package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.config.RateLimitProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RegistreDeSeauxTest {

    private final AtomicLong horloge = new AtomicLong(0L);

    private RegistreDeSeaux registre(int rafale, int clesMax) {
        return new RegistreDeSeaux(
                new RateLimitProperties(true, 60, rafale, clesMax, Duration.ofMinutes(10)),
                horloge::get);
    }

    /** Chaque cle a son propre seau : une boutique bavarde n'epuise pas le plafond d'une autre. */
    @Test
    void lesClesSontIndependantes() {
        RegistreDeSeaux registre = registre(1, 100);

        assertThat(registre.verdict("boutique-a").accepte()).isTrue();
        assertThat(registre.verdict("boutique-a").accepte()).isFalse();
        assertThat(registre.verdict("boutique-b").accepte()).isTrue();
    }

    /** Le verdict de refus porte une attente exploitable pour Retry-After. */
    @Test
    void leRefusPorteUneAttente() {
        RegistreDeSeaux registre = registre(1, 100);
        registre.verdict("boutique-a");

        RegistreDeSeaux.Verdict refus = registre.verdict("boutique-a");
        assertThat(refus.accepte()).isFalse();
        assertThat(refus.attenteSecondes()).isGreaterThanOrEqualTo(1L);
    }

    /** Le balayage evacue les seaux inactifs au-dela de la fenetre. */
    @Test
    void leBalayageEvacueLesSeauxInactifs() {
        RegistreDeSeaux registre = registre(5, 100);
        registre.verdict("boutique-a");
        assertThat(registre.tailleSuivie()).isEqualTo(1);

        horloge.set(Duration.ofMinutes(11).toNanos());
        registre.balaie();

        assertThat(registre.tailleSuivie()).isZero();
    }

    /** Un seau encore actif survit au balayage. */
    @Test
    void leBalayageEpargneLesSeauxActifs() {
        RegistreDeSeaux registre = registre(5, 100);
        registre.verdict("boutique-a");

        horloge.set(Duration.ofMinutes(1).toNanos());
        registre.balaie();

        assertThat(registre.tailleSuivie()).isEqualTo(1);
    }

    /**
     * Le plafond tient meme sous un martelage de cles inconnues. C'est le test qui compte :
     * sans lui, le limiteur serait lui-meme le vecteur d'epuisement memoire.
     */
    @Test
    void laCarteResteBorneeSousMartelage() {
        RegistreDeSeaux registre = registre(5, 50);

        for (int i = 0; i < 5_000; i++) {
            registre.verdict("cle-inventee-" + i);
        }

        assertThat(registre.tailleSuivie()).isLessThanOrEqualTo(50);
    }

    /** Une cle evincee redevient une cle neuve, donc acceptee : l'eviction ne punit personne. */
    @Test
    void uneCleEvinceeRedevientNeuve() {
        RegistreDeSeaux registre = registre(1, 2);
        registre.verdict("victime");
        assertThat(registre.verdict("victime").accepte()).isFalse();

        registre.verdict("intruse-1");
        registre.verdict("intruse-2");
        registre.verdict("intruse-3");

        assertThat(registre.verdict("victime").accepte()).isTrue();
    }
}

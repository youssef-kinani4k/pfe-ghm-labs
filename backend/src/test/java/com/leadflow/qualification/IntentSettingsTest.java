package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * La cle d'API de l'analyse d'intention se regle depuis l'ecran d'administration, et non
 * plus seulement par la variable d'environnement lue au demarrage.
 *
 * <p>Ces tests verrouillent les trois proprietes qui rendent ce reglage sur : la base
 * l'emporte sur l'environnement quand elle porte une cle, la cle est chiffree au repos
 * comme le secret HMAC d'une boutique, et rien de ce qui sort du service ne permet de la
 * reconstituer.
 *
 * <p>{@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche JPA n'inclut pas les
 * beans {@code @Component}, donc le converter de chiffrement ne serait pas cable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "leadflow.intent.gemini.api-key=cle-d-environnement")
class IntentSettingsTest {

    @Autowired private IntentSettings reglages;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void videLaTable() {
        jdbcTemplate.update("DELETE FROM intent_setting");
    }

    @Test
    void sansLigneEnBaseLaCleVientDeLEnvironnement() {
        EtatIntent etat = reglages.etat();

        assertThat(etat.source()).isEqualTo(SourceCle.ENV);
        assertThat(etat.cleDefinie()).isTrue();
        assertThat(reglages.cleEffective()).isEqualTo("cle-d-environnement");
    }

    @Test
    void laCleEnregistreeLEmporteSurLEnvironnement() {
        reglages.enregistre("cle-de-la-console", true);

        assertThat(reglages.etat().source()).isEqualTo(SourceCle.BASE);
        assertThat(reglages.cleEffective()).isEqualTo("cle-de-la-console");
    }

    @Test
    void laCleEstChiffreeAuRepos() {
        reglages.enregistre("cle-de-la-console", true);

        String stocke = jdbcTemplate.queryForObject(
                "SELECT api_key FROM intent_setting", String.class);

        assertThat(stocke).isNotNull().doesNotContain("cle-de-la-console");
    }

    @Test
    void netRendQueLesQuatreDerniersCaracteresDeLaCle() {
        reglages.enregistre("AIzaSyExempleDeCleSecrete", true);

        EtatIntent etat = reglages.etat();

        assertThat(etat.apercu()).isEqualTo("rete");
        assertThat(etat.apercu()).doesNotContain("AIzaSy");
    }

    @Test
    void lInterrupteurCoupeLAnalyseSansEffacerLaCle() {
        reglages.enregistre("cle-de-la-console", false);

        assertThat(reglages.actif()).isFalse();
        assertThat(reglages.etat().cleDefinie()).isTrue();
    }

    @Test
    void unChangementPrendEffetSansRedemarrage() {
        reglages.enregistre("premiere-cle", true);
        assertThat(reglages.cleEffective()).isEqualTo("premiere-cle");

        reglages.enregistre("seconde-cle", true);

        assertThat(reglages.cleEffective()).isEqualTo("seconde-cle");
    }
}

package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * La regle centrale de F3 : seul l'email peut faire echouer la qualification. Tout autre
 * champ illisible est mis a null, jamais transforme en rejet.
 */
class ContactNormalizerTest {

    private final ContactNormalizer normalizer = new ContactNormalizer();

    private static ChampsBruts avecEmail(String email) {
        return new ChampsBruts(email, null, null, null, null, null, null, null);
    }

    private static ChampsBruts avecTelephone(String telephone) {
        return new ChampsBruts("a@b.test", telephone, null, null, null, null, null, null);
    }

    @Test
    void metLEmailEnMinusculesEtRetireLesEspaces() {
        Optional<ContactNormalise> contact = normalizer.normalise(avecEmail("  Karim@ACME.test  "));

        assertThat(contact).isPresent();
        assertThat(contact.get().email()).isEqualTo("karim@acme.test");
    }

    @Test
    void refuseUnEmailSansArobase() {
        assertThat(normalizer.normalise(avecEmail("karim.acme.test"))).isEmpty();
    }

    @Test
    void refuseUnEmailSansPointDansLeDomaine() {
        assertThat(normalizer.normalise(avecEmail("karim@acme"))).isEmpty();
    }

    @Test
    void refuseUnEmailAbsentOuVide() {
        assertThat(normalizer.normalise(avecEmail(null))).isEmpty();
        assertThat(normalizer.normalise(avecEmail("   "))).isEmpty();
    }

    @Test
    void refuseUnEmailTropLongPourLaColonne() {
        String trop = "a".repeat(250) + "@acme.test";
        assertThat(normalizer.normalise(avecEmail(trop))).isEmpty();
    }

    @Test
    void retireLesSeparateursDuTelephone() {
        assertThat(normalizer.normalise(avecTelephone("06 12-34.56 78")).orElseThrow().phone())
                .isEqualTo("0612345678");
    }

    @Test
    void conserveLeIndicatifInternational() {
        assertThat(normalizer.normalise(avecTelephone("+212 6 12 34 56 78")).orElseThrow().phone())
                .isEqualTo("+212612345678");
    }

    @Test
    void convertitLeDoubleZeroInitialEnPlus() {
        assertThat(normalizer.normalise(avecTelephone("00212612345678")).orElseThrow().phone())
                .isEqualTo("+212612345678");
    }

    @Test
    void metLeTelephoneANullSansRejeterLeLead() {
        Optional<ContactNormalise> contact = normalizer.normalise(avecTelephone("123"));

        assertThat(contact).isPresent();
        assertThat(contact.get().phone()).isNull();
    }

    @Test
    void tronqueLesTextesAuxLongueursDeColonne() {
        ChampsBruts bruts = new ChampsBruts(
                "a@b.test", null, null, "S".repeat(200), "P".repeat(300), "N".repeat(300),
                null, "X".repeat(200));

        ContactNormalise contact = normalizer.normalise(bruts).orElseThrow();

        assertThat(contact.companyName()).hasSize(160);
        assertThat(contact.firstName()).hasSize(80);
        assertThat(contact.lastName()).hasSize(80);
        assertThat(contact.sector()).hasSize(80);
    }

    @Test
    void reduitLesEspacesMultiplesDesTextes() {
        ChampsBruts bruts = new ChampsBruts(
                "a@b.test", null, null, "  ACME   Industries  ", null, null, null, null);

        assertThat(normalizer.normalise(bruts).orElseThrow().companyName())
                .isEqualTo("ACME Industries");
    }

    @Test
    void neRetientLePaysQueSurDeuxLettres() {
        ChampsBruts deuxLettres = new ChampsBruts(
                "a@b.test", null, null, null, null, null, "ma", null);
        ChampsBruts troisLettres = new ChampsBruts(
                "a@b.test", null, null, null, null, null, "Maroc", null);

        assertThat(normalizer.normalise(deuxLettres).orElseThrow().countryCode()).isEqualTo("MA");
        assertThat(normalizer.normalise(troisLettres).orElseThrow().countryCode()).isNull();
    }

    @Test
    void conserveLeMessageEntier() {
        String long_ = "Bonjour ".repeat(1000);
        ChampsBruts bruts = new ChampsBruts(
                "a@b.test", null, long_, null, null, null, null, null);

        assertThat(normalizer.normalise(bruts).orElseThrow().message())
                .hasSize(long_.trim().length());
    }

    /** Le message part tel quel vers l'analyseur et vers l'ERP : ses paragraphes survivent. */
    @Test
    void conserveLesSautsDeLigneDuMessage() {
        String message = "Bonjour,\n\nJe veux un devis.";
        ChampsBruts bruts = new ChampsBruts(
                "a@b.test", null, "  " + message + "  ", null, null, null, null, null);

        assertThat(normalizer.normalise(bruts).orElseThrow().message()).isEqualTo(message);
    }
}

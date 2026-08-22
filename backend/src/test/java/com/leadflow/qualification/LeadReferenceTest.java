package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La propriete qui compte n'est pas la forme mais le determinisme : c'est lui qui rend le
 * rejeu d'une synchronisation ERP inoffensif.
 */
class LeadReferenceTest {

    private static final UUID LEAD =
            UUID.fromString("3f2a9c1b-7d4e-4a12-9f03-0a1b2c3d4e5f");

    @Test
    void rendLaMemeReferencePourLeMemeIdentifiant() {
        assertThat(LeadReference.pour(LEAD)).isEqualTo(LeadReference.pour(LEAD));
    }

    @Test
    void deriveLaReferenceDUneEmpreinteDeLIdentifiant() {
        assertThat(LeadReference.pour(LEAD)).isEqualTo("LF-682775F7969B");
    }

    /**
     * Aucun caractere de l'identifiant ne se retrouve tel quel dans la reference : c'est ce
     * qui affranchit la classe de la disposition interne de l'UUID, dont deux parties se
     * sont deja revelees non aleatoires.
     */
    @Test
    void neRecopieAucuneTrancheDeLIdentifiant() {
        String hexadecimal = LEAD.toString().replace("-", "").toUpperCase();
        String reference = LeadReference.pour(LEAD).substring(3);

        assertThat(hexadecimal).doesNotContain(reference);
    }

    /**
     * Le defaut trouve en verifiant F4 contre un vrai Dolibarr. Les 64 bits de poids fort
     * de {@code Style.TIME} portent l'adresse IP et un identifiant de JVM, constants pour
     * toute la duree d'un demarrage : une reference tiree de la tete de l'UUID etait la
     * meme pour dix leads consecutifs, et l'ERP rendait l'opportunite deja creee au lieu
     * d'en creer une. Les deux identifiants ci-dessous sont reels, tires de cette panne.
     */
    @Test
    void distingueDeuxIdentifiantsQuiPartagentLeursBitsDePoidsFort() {
        UUID premier = UUID.fromString("a9fed6e8-a02b-1cd1-81a0-2bb43afc0005");
        UUID second = UUID.fromString("a9fed6e8-a02b-1cd1-81a0-2bc41d500014");

        assertThat(LeadReference.pour(premier)).isNotEqualTo(LeadReference.pour(second));
    }

    @Test
    void distingueDeuxIdentifiantsDifferents() {
        UUID autre = UUID.fromString("11112222-3333-4444-5555-666677778888");
        assertThat(LeadReference.pour(autre)).isNotEqualTo(LeadReference.pour(LEAD));
    }

    @Test
    void tientDansLaColonneRefDeDolibarr() {
        assertThat(LeadReference.pour(UUID.randomUUID())).hasSizeLessThanOrEqualTo(32);
    }

    @Test
    void refuseUnIdentifiantNul() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> LeadReference.pour(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

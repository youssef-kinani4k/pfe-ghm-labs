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
    void deriveLaReferenceDesPremiersCaracteresDeLIdentifiant() {
        assertThat(LeadReference.pour(LEAD)).isEqualTo("LF-3F2A9C1B7D4E");
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

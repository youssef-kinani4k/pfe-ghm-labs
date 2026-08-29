package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.qualification.LeadIntent;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Le garde-fou du cycle de packages : {@code tenant/} ecrit un document que
 * {@code qualification/} relit, et rien dans la structure du code n'empeche les deux formes
 * de diverger. Ce test, oui.
 */
class ScoringAllerRetourTest {

    @Test
    void leDocumentEcritEstReluIdentiquement() {
        ScoringForm formulaire = new ScoringForm(
                20, 12, 6, 8,
                Map.of(
                        LeadIntent.DEVIS, 45,
                        LeadIntent.ACHAT, 35,
                        LeadIntent.INFORMATION, 10,
                        LeadIntent.SUPPORT, 4,
                        LeadIntent.AUTRE, 1),
                Set.of("industrie"), Set.of("FR"), 9, 65);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.telephonePresent()).isEqualTo(20);
        assertThat(relu.societePresente()).isEqualTo(12);
        assertThat(relu.nomPresent()).isEqualTo(6);
        assertThat(relu.messagePresent()).isEqualTo(8);
        assertThat(relu.intention()).containsEntry(LeadIntent.DEVIS, 45);
        assertThat(relu.intention()).containsEntry(LeadIntent.AUTRE, 1);
        assertThat(relu.secteursCibles()).containsExactly("industrie");
        assertThat(relu.paysCibles()).containsExactly("FR");
        assertThat(relu.bonusCible()).isEqualTo(9);
        assertThat(relu.seuilChaud()).isEqualTo(65);
    }

    /** L'ecran montre la normalisation au lieu de la subir : ce que l'on relit est normalise. */
    @Test
    void lesListesCiblesSontNormaliseesALaRelecture() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(), Set.of("  Industrie  "), Set.of("fr"), 10, 70);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.secteursCibles()).containsExactly("industrie");
        assertThat(relu.paysCibles()).containsExactly("FR");
    }

    /** Une intention absente du formulaire garde le poids par defaut, jamais zero. */
    @Test
    void uneIntentionAbsenteGardeLeDefaut() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(LeadIntent.DEVIS, 45), Set.of(), Set.of(), 10, 70);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.intention()).containsEntry(LeadIntent.DEVIS, 45);
        assertThat(relu.intention()).containsEntry(LeadIntent.ACHAT, 40);
    }

    @Test
    void laVueCalculeLeMaximumAtteignableEtSignaleUnSeuilHorsDePortee() {
        ScoringView pardefaut = ScoringView.de(ScoringConfig.defaut());

        // 15 + 10 + 5 + 10 + 40 (meilleure intention) + 10 (bonus) = 90
        assertThat(pardefaut.scoreMaximum()).isEqualTo(90);
        assertThat(pardefaut.seuilInatteignable()).isFalse();
        assertThat(pardefaut.defauts().seuilChaud()).isEqualTo(70);

        ScoringForm maigre = new ScoringForm(
                1, 1, 1, 1, Map.of(LeadIntent.DEVIS, 5), Set.of(), Set.of(), 0, 80);
        ScoringView vue = ScoringView.de(ScoringConfig.depuis(maigre.versDocument()));

        assertThat(vue.seuilInatteignable()).isTrue();
    }
}

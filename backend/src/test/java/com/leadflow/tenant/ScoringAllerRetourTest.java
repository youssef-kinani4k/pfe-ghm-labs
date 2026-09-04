package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.qualification.LeadIntent;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import java.util.List;
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
                Set.of("industrie"), Set.of("FR"), 9, 65, 70);

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

    @Test
    void leSeuilDeNotificationSurvitAAllerRetourEtResteDistinctDuSeuilChaud() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(), Set.of(), Set.of(), 10, 65, 85);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        // Deux seuils, deux questions : colorer une pastille et deranger quelqu'un. Les
        // confondre ferait qu'ajuster l'affichage changerait qui recoit des e-mails.
        assertThat(relu.seuilChaud()).isEqualTo(65);
        assertThat(relu.seuilNotification()).isEqualTo(85);
    }

    @Test
    void unDocumentSansSeuilDeNotificationPrendLeDefaut() {
        // La lecture est tolerante : toutes les boutiques configurees avant F12 continuent
        // de fonctionner sans etre touchees, et c'est ce qui evite une migration.
        Map<String, Object> ancien = new java.util.LinkedHashMap<>(
                new ScoringForm(15, 10, 5, 10, Map.of(), Set.of(), Set.of(), 10, 70, 70)
                        .versDocument());
        ancien.remove("seuilNotification");

        assertThat(ScoringConfig.depuis(ancien).seuilNotification())
                .isEqualTo(ScoringConfig.defaut().seuilNotification());
    }

    @Test
    void unFormulaireSansSeuilDeNotificationPrendLeDefautEtNonZero() {
        // Le PUT remplace le document entier. Un client ecrit avant F12 n'envoie pas le
        // champ : s'il valait alors zero, la boutique se mettrait a notifier TOUS ses
        // leads sans que personne ne l'ait demande. Le defaut est la seule valeur sure.
        ScoringForm sansLeChamp = new ScoringForm(
                15, 10, 5, 10, Map.of(), Set.of(), Set.of(), 10, 70, null);

        assertThat(sansLeChamp.seuilNotification())
                .isEqualTo(ScoringConfig.defaut().seuilNotification());
    }

    @Test
    void laVueSignaleUnSeuilDeNotificationHorsDePortee() {
        // Meme raison que pour seuilChaud : un seuil qu'aucun lead ne peut atteindre n'est
        // pas une erreur de saisie, c'est un etat qu'il faut voir — ici, « personne ne sera
        // jamais prevenu ».
        ScoringForm maigre = new ScoringForm(
                1, 1, 1, 1, Map.of(LeadIntent.DEVIS, 5), Set.of(), Set.of(), 0, 5, 80);

        ScoringView vue = ScoringView.de(ScoringConfig.depuis(maigre.versDocument()));

        assertThat(vue.seuilInatteignable()).isFalse();
        assertThat(vue.notificationInatteignable()).isTrue();
    }

    /** L'ecran montre la normalisation au lieu de la subir : ce que l'on relit est normalise. */
    @Test
    void lesListesCiblesSontNormaliseesALaRelecture() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(), Set.of("  Industrie  "), Set.of("fr"), 10, 70, 70);

        ScoringConfig relu = ScoringConfig.depuis(formulaire.versDocument());

        assertThat(relu.secteursCibles()).containsExactly("industrie");
        assertThat(relu.paysCibles()).containsExactly("FR");
    }

    /** Une intention absente du formulaire garde le poids par defaut, jamais zero. */
    @Test
    void uneIntentionAbsenteGardeLeDefaut() {
        ScoringForm formulaire = new ScoringForm(
                15, 10, 5, 10, Map.of(LeadIntent.DEVIS, 45), Set.of(), Set.of(), 10, 70, 70);

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
                1, 1, 1, 1, Map.of(LeadIntent.DEVIS, 5), Set.of(), Set.of(), 0, 80, 70);
        ScoringView vue = ScoringView.de(ScoringConfig.depuis(maigre.versDocument()));

        assertThat(vue.seuilInatteignable()).isTrue();
    }

    /**
     * Les bornes de {@code ScoringForm} ne tiennent qu'a l'ecriture : la lecture passe par
     * {@code ScoringConfig.depuis}, tolerante par construction. Un document ecrit avant F8, ou
     * pose a la main en base, peut donc porter des poids negatifs — et la vue doit alors
     * annoncer ce que {@code LeadScorer} rendrait vraiment, c'est-a-dire zero, jamais un
     * maximum negatif.
     */
    @Test
    void unDocumentAPoidsNegatifsNeRendJamaisUnMaximumNegatif() {
        Map<String, Object> document = Map.of(
                "poids",
                Map.of(
                        "telephonePresent", -50,
                        "societePresente", -40,
                        "nomPresent", -30,
                        "messagePresent", -20,
                        "intention", Map.of("DEVIS", -10, "ACHAT", -10, "INFORMATION", -10,
                                "SUPPORT", -10, "AUTRE", -10)),
                "secteursCibles", List.of(),
                "paysCibles", List.of(),
                "bonusCible", -5,
                "seuilChaud", 70);

        ScoringView vue = ScoringView.de(ScoringConfig.depuis(document));

        assertThat(vue.scoreMaximum()).isZero();
        assertThat(vue.seuilInatteignable()).isTrue();
    }

    /** Le pendant haut, deja porte par le code mais jamais eprouve : le total est plafonne. */
    @Test
    void unDocumentAPoidsExcessifsResteBorneACent() {
        ScoringForm genereux = new ScoringForm(
                100, 100, 100, 100, Map.of(LeadIntent.DEVIS, 100), Set.of(), Set.of(), 100, 70, 70);

        ScoringView vue = ScoringView.de(ScoringConfig.depuis(genereux.versDocument()));

        assertThat(vue.scoreMaximum()).isEqualTo(100);
        assertThat(vue.seuilInatteignable()).isFalse();
    }
}

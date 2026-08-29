package com.leadflow.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.qualification.LeadIntent;
import com.leadflow.tenant.dto.ScoringForm;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Les contraintes de {@code ScoringForm} qui portent sur des <b>elements de conteneur</b> —
 * les valeurs de {@code intention}, les secteurs, les pays — eprouvees directement sur le
 * validateur, sans contexte Spring.
 *
 * <p>Elles meritent leur propre test parce qu'elles peuvent compiler sans jamais s'appliquer :
 * {@code @Min} porte {@code TYPE_USE} dans son {@code @Target}, donc l'ecrire sur un argument
 * de type est toujours accepte, et qu'elle soit reellement validee depend de la propagation de
 * l'annotation sur le champ du record. {@code ScoringValidationTest} n'eprouve que les champs
 * plats : une contrainte de conteneur inactive y passerait inapercue, alors que le Javadoc de
 * {@code ScoringForm} affirme que les bornes tiennent.
 *
 * <p>Sans contexte Spring, et donc sans conteneur : ces contraintes ne dependent que du
 * validateur, et les eprouver a travers MockMvc couterait les trois cents secondes de
 * demarrage pour ne rien prouver de plus.
 */
class ScoringContraintesTest {

    private static ValidatorFactory fabrique;
    private static Validator validateur;

    @BeforeAll
    static void demarre() {
        fabrique = Validation.buildDefaultValidatorFactory();
        validateur = fabrique.getValidator();
    }

    @AfterAll
    static void arrete() {
        fabrique.close();
    }

    /** Le temoin : sans lui, un test qui trouve des violations partout passerait aussi. */
    @Test
    void unBaremeCorrectNeVioleRien() {
        assertThat(validateur.validate(bareme(40, "industrie", "FR"))).isEmpty();
    }

    /** Meme raison que les poids plats : au-dela de 100, LeadScorer plafonne deja le total. */
    @Test
    void unPoidsDIntentionHorsBornesEstRefuse() {
        assertThat(violations(bareme(5000, "industrie", "FR")))
                .containsExactly("intention[DEVIS].<map value>");
        assertThat(violations(bareme(-1, "industrie", "FR")))
                .containsExactly("intention[DEVIS].<map value>");
    }

    @Test
    void unSecteurTropLongEstRefuse() {
        assertThat(violations(bareme(40, "s".repeat(81), "FR")))
                .hasSize(1)
                .allMatch(chemin -> chemin.startsWith("secteursCibles["));
    }

    @Test
    void unPaysQuiNEstPasUnCodeDeDeuxLettresEstRefuse() {
        assertThat(violations(bareme(40, "industrie", "FRA")))
                .hasSize(1)
                .allMatch(chemin -> chemin.startsWith("paysCibles["));
        assertThat(violations(bareme(40, "industrie", "F1")))
                .hasSize(1)
                .allMatch(chemin -> chemin.startsWith("paysCibles["));
    }

    private static ScoringForm bareme(int poidsDevis, String secteur, String pays) {
        return new ScoringForm(
                15,
                10,
                5,
                10,
                Map.of(LeadIntent.DEVIS, poidsDevis),
                Set.of(secteur),
                Set.of(pays),
                10,
                70);
    }

    private static java.util.List<String> violations(ScoringForm formulaire) {
        return validateur.validate(formulaire).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .toList();
    }
}

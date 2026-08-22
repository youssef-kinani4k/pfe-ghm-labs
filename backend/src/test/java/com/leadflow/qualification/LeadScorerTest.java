package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * La lecture du document est deliberement tolerante : un client mal configure doit produire
 * un score discutable, jamais un lead perdu.
 */
class LeadScorerTest {

    private final LeadScorer scorer = new LeadScorer();

    private static ContactNormalise contact(
            String phone, String societe, String nom, String message, String pays, String secteur) {
        return new ContactNormalise(
                "a@b.test", phone, message, societe, null, nom, pays, secteur);
    }

    private static ContactNormalise nu() {
        return contact(null, null, null, null, null, null);
    }

    @Test
    void appliqueLeBaremeParDefautSurUnDocumentVide() {
        int score = scorer.score(
                contact("+212600000000", "ACME", "Bennani", "Je veux un devis", null, null),
                LeadIntent.DEVIS,
                Map.of());

        // 15 telephone + 10 societe + 5 nom + 10 message + 40 intention DEVIS
        assertThat(score).isEqualTo(80);
    }

    @Test
    void neCompteQueCeQuiEstPresent() {
        assertThat(scorer.score(nu(), LeadIntent.AUTRE, Map.of())).isZero();
    }

    @Test
    void appliqueLesPoidsSurcharges() {
        Map<String, Object> document = Map.of(
                "poids", Map.of("telephonePresent", 50, "intention", Map.of("DEVIS", 10)));

        int score = scorer.score(
                contact("+212600000000", null, null, null, null, null),
                LeadIntent.DEVIS,
                document);

        assertThat(score).isEqualTo(60);
    }

    @Test
    void completeUnDocumentPartielParLesDefauts() {
        Map<String, Object> document = Map.of("poids", Map.of("nomPresent", 25));

        int score = scorer.score(
                contact("+212600000000", null, "Bennani", null, null, null),
                LeadIntent.AUTRE,
                document);

        // 15 telephone par defaut + 25 nom surcharge
        assertThat(score).isEqualTo(40);
    }

    @Test
    void ignoreLesClesInconnues() {
        Map<String, Object> document = Map.of(
                "poids", Map.of("cleQuiNExistePas", 99),
                "autreCleInconnue", "peu importe");

        assertThat(scorer.score(nu(), LeadIntent.AUTRE, document)).isZero();
    }

    @Test
    void retombeSurLesDefautsQuandLeDocumentEstMalforme() {
        Map<String, Object> document = Map.of("poids", "ceci devrait etre un objet");

        int score = scorer.score(
                contact("+212600000000", null, null, null, null, null),
                LeadIntent.AUTRE,
                document);

        assertThat(score).isEqualTo(15);
    }

    @Test
    void ajouteLeBonusQuandLeSecteurEstCible() {
        Map<String, Object> document = Map.of(
                "secteursCibles", List.of("industrie", "btp"), "bonusCible", 10);

        assertThat(scorer.score(contact(null, null, null, null, null, "Industrie"),
                LeadIntent.AUTRE, document))
                .isEqualTo(10);
    }

    @Test
    void ajouteLeBonusQuandLePaysEstCible() {
        Map<String, Object> document = Map.of("paysCibles", List.of("MA", "FR"), "bonusCible", 7);

        assertThat(scorer.score(contact(null, null, null, null, "MA", null),
                LeadIntent.AUTRE, document))
                .isEqualTo(7);
    }

    @Test
    void neCompteLeBonusQuUneSeuleFoisMemeSiLesDeuxCiblesCorrespondent() {
        Map<String, Object> document = Map.of(
                "secteursCibles", List.of("btp"),
                "paysCibles", List.of("MA"),
                "bonusCible", 10);

        assertThat(scorer.score(contact(null, null, null, null, "MA", "btp"),
                LeadIntent.AUTRE, document))
                .isEqualTo(10);
    }

    @Test
    void borneLeScoreACentQuandLaSommeDeborde() {
        Map<String, Object> document = Map.of(
                "poids", Map.of("telephonePresent", 90, "intention", Map.of("DEVIS", 90)));

        assertThat(scorer.score(contact("+212600000000", null, null, null, null, null),
                LeadIntent.DEVIS, document))
                .isEqualTo(100);
    }

    @Test
    void borneLeScoreAZeroQuandLesPoidsSontNegatifs() {
        Map<String, Object> document = Map.of("poids", Map.of("telephonePresent", -50));

        assertThat(scorer.score(contact("+212600000000", null, null, null, null, null),
                LeadIntent.AUTRE, document))
                .isZero();
    }

    @Test
    void tolereUnDocumentNul() {
        assertThat(scorer.score(nu(), LeadIntent.AUTRE, null)).isZero();
    }
}

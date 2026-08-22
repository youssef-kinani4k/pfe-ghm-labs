package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Cet analyseur est le mode degrade : sa propriete la plus importante n'est pas sa
 * justesse, c'est qu'il ne peut structurellement pas echouer.
 */
class RuleBasedIntentAnalyzerTest {

    private final RuleBasedIntentAnalyzer analyseur = new RuleBasedIntentAnalyzer();

    @Test
    void detecteUneDemandeDeDevis() {
        assertThat(analyseur.analyse("Je veux un devis pour 50 unites").intent())
                .isEqualTo(LeadIntent.DEVIS);
    }

    @Test
    void detecteUneQuestionDePrix() {
        assertThat(analyseur.analyse("Combien ca coute ?").intent())
                .isEqualTo(LeadIntent.DEVIS);
    }

    @Test
    void detecteUneIntentionDAchat() {
        assertThat(analyseur.analyse("Je souhaite commander rapidement").intent())
                .isEqualTo(LeadIntent.ACHAT);
    }

    @Test
    void detecteUneDemandeDInformation() {
        assertThat(analyseur.analyse("Pouvez-vous m'envoyer votre catalogue").intent())
                .isEqualTo(LeadIntent.INFORMATION);
    }

    @Test
    void detecteUneDemandeDeSupport() {
        assertThat(analyseur.analyse("J'ai un probleme, le produit est defectueux").intent())
                .isEqualTo(LeadIntent.SUPPORT);
    }

    @Test
    void ignoreLesAccentsEtLaCasse() {
        assertThat(analyseur.analyse("Je veux un DEVIS").intent()).isEqualTo(LeadIntent.DEVIS);
        assertThat(analyseur.analyse("Quel est le prix ?").intent()).isEqualTo(LeadIntent.DEVIS);
    }

    @Test
    void neConfondPasUnMotAvecUnFragmentDeMot() {
        assertThat(analyseur.analyse("prixe devisage").intent()).isEqualTo(LeadIntent.AUTRE);
    }

    @Test
    void rendAutreQuandAucunMotDuLexiqueNApparait() {
        assertThat(analyseur.analyse("Bonjour, bonne journee a vous").intent())
                .isEqualTo(LeadIntent.AUTRE);
    }

    @Test
    void rendAutreSurUnMessageVideOuNulSansJamaisEchouer() {
        assertThatCode(() -> analyseur.analyse(null)).doesNotThrowAnyException();
        assertThat(analyseur.analyse(null).intent()).isEqualTo(LeadIntent.AUTRE);
        assertThat(analyseur.analyse("   ").intent()).isEqualTo(LeadIntent.AUTRE);
    }

    @Test
    void seDeclareToujoursCommeSourceDeRegles() {
        assertThat(analyseur.analyse("Je veux un devis").source()).isEqualTo(IntentSource.RULES);
        assertThat(analyseur.analyse(null).source()).isEqualTo(IntentSource.RULES);
    }
}

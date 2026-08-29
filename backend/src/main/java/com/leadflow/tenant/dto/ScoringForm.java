package com.leadflow.tenant.dto;

import com.leadflow.qualification.LeadIntent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bareme d'une boutique tel qu'il se saisit et se rend.
 *
 * <p>Ce record vit dans {@code tenant/} mais decrit un document que {@code qualification/}
 * relit : {@code ScoringAllerRetourTest} est ce qui empeche les deux formes de diverger. Le
 * jeu de criteres est ferme, comme {@code ScoringConfig} — seuls les poids et les listes
 * cibles se reglent.
 *
 * <p>Les bornes {@code [0, 100]} des poids, du bonus et du seuil viennent du bornage du score
 * lui-meme : {@code LeadScorer} plafonne le total a 100, donc un poids au-dela de 100 n'aurait
 * aucun effet observable — ce n'est pas une valeur arbitraire, c'est la limite au-dela de
 * laquelle le champ devient inerte. Le seuil de chaleur partage cette borne pour la meme
 * raison, mais un seuil au-dessus du maximum reellement atteignable par la combinaison des
 * poids n'est pas refuse ici : c'est un etat legitime, signale par {@code ScoringView}, pas
 * une erreur de saisie.
 */
public record ScoringForm(
        @Min(0) @Max(100) int telephonePresent,
        @Min(0) @Max(100) int societePresente,
        @Min(0) @Max(100) int nomPresent,
        @Min(0) @Max(100) int messagePresent,
        @NotNull Map<LeadIntent, @Min(0) @Max(100) Integer> intention,
        @NotNull Set<@Size(max = 80) String> secteursCibles,
        @NotNull Set<@Pattern(regexp = "(?i)[a-z]{2}") String> paysCibles,
        @Min(0) @Max(100) int bonusCible,
        @Min(0) @Max(100) int seuilChaud) {

    /** Document tel qu'il part dans {@code client.scoring_config}. */
    public Map<String, Object> versDocument() {
        Map<String, Object> poids = new LinkedHashMap<>();
        poids.put("telephonePresent", telephonePresent);
        poids.put("societePresente", societePresente);
        poids.put("nomPresent", nomPresent);
        poids.put("messagePresent", messagePresent);

        Map<String, Object> intentions = new LinkedHashMap<>();
        intention.forEach((cle, valeur) -> intentions.put(cle.name(), valeur));
        poids.put("intention", intentions);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("poids", poids);
        document.put("secteursCibles", List.copyOf(secteursCibles));
        document.put("paysCibles", List.copyOf(paysCibles));
        document.put("bonusCible", bonusCible);
        document.put("seuilChaud", seuilChaud);
        return document;
    }

    public static ScoringForm de(com.leadflow.qualification.ScoringConfig bareme) {
        return new ScoringForm(
                bareme.telephonePresent(),
                bareme.societePresente(),
                bareme.nomPresent(),
                bareme.messagePresent(),
                bareme.intention(),
                bareme.secteursCibles(),
                bareme.paysCibles(),
                bareme.bonusCible(),
                bareme.seuilChaud());
    }
}

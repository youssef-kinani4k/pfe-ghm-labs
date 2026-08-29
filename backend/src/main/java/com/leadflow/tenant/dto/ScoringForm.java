package com.leadflow.tenant.dto;

import com.leadflow.qualification.LeadIntent;
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
 */
public record ScoringForm(
        int telephonePresent,
        int societePresente,
        int nomPresent,
        int messagePresent,
        Map<LeadIntent, Integer> intention,
        Set<String> secteursCibles,
        Set<String> paysCibles,
        int bonusCible,
        int seuilChaud) {

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

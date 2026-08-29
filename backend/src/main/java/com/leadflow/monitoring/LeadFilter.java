package com.leadflow.monitoring;

import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Criteres de recherche, tous facultatifs. Un record avec des fabriques « avec... » plutot
 * qu'une dizaine de parametres de methode : les appels du controleur restent lisibles, et
 * les tests ne construisent que ce qu'ils eprouvent.
 *
 * <p>{@code chaud} est un {@code Boolean} et non un {@code boolean} : {@code null} veut dire
 * « pas de filtre », et {@code false} n'est pas la negation de {@code true}. Un lead tiede
 * n'a aucune raison d'etre demande specifiquement, alors qu'un filtre absent doit tout
 * rendre — seul {@code TRUE} pose une clause.
 */
public record LeadFilter(
        UUID clientId,
        List<LeadStatus> statuts,
        String intent,
        IntentSource intentSource,
        UUID salesRepId,
        Integer minScore,
        Instant from,
        Instant to,
        String recherche,
        Boolean chaud) {

    public static LeadFilter vide() {
        return new LeadFilter(null, null, null, null, null, null, null, null, null, null);
    }

    public LeadFilter avecClientId(UUID valeur) {
        return new LeadFilter(valeur, statuts, intent, intentSource, salesRepId, minScore,
                from, to, recherche, chaud);
    }

    public LeadFilter avecStatuts(List<LeadStatus> valeur) {
        return new LeadFilter(clientId, valeur, intent, intentSource, salesRepId, minScore,
                from, to, recherche, chaud);
    }

    public LeadFilter avecMinScore(Integer valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, valeur,
                from, to, recherche, chaud);
    }

    public LeadFilter avecRecherche(String valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, minScore,
                from, to, valeur, chaud);
    }

    public LeadFilter avecChaud(Boolean valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, minScore,
                from, to, recherche, valeur);
    }
}

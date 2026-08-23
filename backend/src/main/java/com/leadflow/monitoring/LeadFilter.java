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
        String recherche) {

    public static LeadFilter vide() {
        return new LeadFilter(null, null, null, null, null, null, null, null, null);
    }

    public LeadFilter avecClientId(UUID valeur) {
        return new LeadFilter(valeur, statuts, intent, intentSource, salesRepId, minScore,
                from, to, recherche);
    }

    public LeadFilter avecStatuts(List<LeadStatus> valeur) {
        return new LeadFilter(clientId, valeur, intent, intentSource, salesRepId, minScore,
                from, to, recherche);
    }

    public LeadFilter avecMinScore(Integer valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, valeur,
                from, to, recherche);
    }

    public LeadFilter avecRecherche(String valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, minScore,
                from, to, valeur);
    }
}

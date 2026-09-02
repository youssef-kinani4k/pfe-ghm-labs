package com.leadflow.monitoring.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Un fait de la vie d'un lead.
 *
 * <p>Un record plat et typé plutot qu'une hierarchie scellee : les huit types partagent la
 * meme forme, et une hierarchie couterait une deserialisation polymorphe cote Angular pour
 * aucun gain.
 *
 * <p><b>Aucune phrase n'est composee ici.</b> Le backend rend des faits typés, le template
 * Angular les met en francais. C'est deliberement la regle inverse de {@code replayWarning},
 * qui reste calcule cote serveur parce qu'il encode une <b>decision</b> du backend — la non
 * idempotence du tour de role — et non un libelle.
 *
 * @param at nul pour une attribution anterieure a la migration V7. L'absence est une
 *     information : la remplacer par une date deduite mentirait.
 */
public record TimelineEntry(
        TimelineEventType type,
        Instant at,
        TimelineOutcome outcome,
        Map<String, String> details) {
}

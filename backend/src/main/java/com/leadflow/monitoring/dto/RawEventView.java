package com.leadflow.monitoring.dto;

import com.leadflow.capture.RawLeadEventStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * La charge utile brute est incluse deliberement : c'est l'ecran ou l'on repond a « pourquoi
 * ce lead n'a pas de telephone » ou « pourquoi cet evenement est DISCARDED », et sans elle
 * la reponse demande un acces psql. Elle ne contient aucun secret — ceux-ci sont sur la
 * ligne client, chiffres, et ne sortent jamais.
 */
public record RawEventView(
        UUID id,
        String source,
        Instant receivedAt,
        RawLeadEventStatus status,
        String failureReason,
        Map<String, Object> payload) {
}

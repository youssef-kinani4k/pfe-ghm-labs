package com.leadflow.capture;

import java.time.Instant;
import java.util.UUID;

/**
 * Evenement applicatif interne, publie dans la transaction de capture et consomme apres
 * son commit. Distinct de {@link CapturedLeadMessage} a dessein : l'un circule dans la JVM,
 * l'autre est un contrat inter-services qu'on ne veut pas faire bouger par accident.
 */
public record LeadCapturedEvent(
        UUID eventId,
        UUID clientId,
        String source,
        Instant receivedAt) {
}

package com.leadflow.capture;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.captured}. C'est la frontiere publique de la
 * couche capture : F3 ne connaitra rien d'autre d'elle.
 *
 * <p>Une reference, pas un contenu. La base reste l'unique source de verite et le
 * consommateur relit {@code raw_lead_event.payload} : un rejeu depuis la DLQ travaille donc
 * forcement sur la donnee a jour, et le payload n'existe pas en double.
 *
 * <p><b>Le consommateur doit etre idempotent sur {@code eventId}.</b> Si une publication
 * reussit mais que le passage a PUBLISHED echoue, le filet de republication renverra le
 * message : la livraison est at-least-once, jamais exactly-once.
 */
public record CapturedLeadMessage(
        UUID eventId,
        UUID clientId,
        String source,
        Instant receivedAt) {
}

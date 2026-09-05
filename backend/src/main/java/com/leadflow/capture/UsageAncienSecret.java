package com.leadflow.capture;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Quand un lead a-t-il ete signe pour la derniere fois avec le secret precedent d'une
 * boutique ?
 *
 * <p>Existe pour que {@code tenant/} n'ait pas a ouvrir un repository sur
 * {@code raw_lead_event}, qui appartient a cette etape. Chaque package garde sa table, et
 * l'administration ne sait pas ou l'information est rangee.
 */
@Component
public class UsageAncienSecret {

    private final RawLeadEventRepository evenements;

    public UsageAncienSecret(RawLeadEventRepository evenements) {
        this.evenements = evenements;
    }

    /** Vide quand aucun lead de cette boutique n'a jamais ete signe avec l'ancien secret. */
    public Optional<Instant> dernierUsage(UUID clientId) {
        return evenements
                .findFirstByClientIdAndSignedWithPreviousSecretTrueOrderByReceivedAtDesc(clientId)
                .map(RawLeadEvent::getReceivedAt);
    }
}

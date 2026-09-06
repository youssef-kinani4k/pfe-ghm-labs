package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.reassigned} : une reattribution manuelle vient
 * d'avoir lieu, et l'ERP ne le sait pas encore.
 *
 * <p>Une reference, pas un contenu, comme {@link RoutedLeadMessage} : le consommateur relit
 * la base, donc un rejeu depuis la DLQ travaille sur la donnee a jour.
 *
 * <p>{@code previousSalesRepId} voyage sans etre utilise pour decider quoi que ce soit :
 * il rend le message lisible dans le journal des morts, ou l'operateur voit ce que la
 * propagation devait corriger.
 *
 * <p><b>Le consommateur est idempotent par nature</b> : poser un responsable est une
 * ecriture, pas une rotation. C'est ce qui distingue cette cle de {@code lead.qualified},
 * dont le rejeu decale le tour de role.
 */
public record LeadReassignedMessage(
        UUID leadId,
        UUID clientId,
        UUID previousSalesRepId,
        UUID newSalesRepId,
        Instant reassignedAt) {
}

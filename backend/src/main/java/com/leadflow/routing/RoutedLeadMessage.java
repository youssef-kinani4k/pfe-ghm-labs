package com.leadflow.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.routed}. C'est la frontiere publique du routage.
 *
 * <p>Une reference, pas un contenu — meme raison qu'en F2 et F3. La base reste l'unique
 * source de verite et le consommateur relit la ligne {@code lead} : un rejeu depuis la DLQ
 * travaille donc forcement sur la donnee a jour.
 *
 * <p>{@code salesRepId} voyage parce que c'est la decision que cette etape vient de prendre :
 * un consommateur qui recoit le message sait ce qui a ete decide sans relire la ligne.
 *
 * <p><b>Le consommateur doit etre idempotent sur {@code leadId}.</b> Un lead deja attribue
 * est republie sans etre reattribue : c'est voulu, et {@code CrmSyncService} absorbe ce
 * rejeu grace a {@code CrmSyncState}.
 */
public record RoutedLeadMessage(
        UUID leadId,
        UUID clientId,
        UUID salesRepId,
        Instant routedAt) {
}

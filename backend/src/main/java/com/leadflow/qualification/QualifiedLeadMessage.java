package com.leadflow.qualification;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.qualified}. C'est la frontiere publique de la
 * qualification : F4 ne connaitra rien d'autre d'elle.
 *
 * <p>Une reference, pas un contenu — meme raison qu'en F2 pour {@code CapturedLeadMessage}.
 * La base reste l'unique source de verite et le consommateur relit la ligne {@code lead} :
 * un rejeu depuis la DLQ travaille donc forcement sur la donnee a jour.
 *
 * <p>{@code score} voyage malgre tout, bien qu'il soit relisible en base : c'est le seul
 * champ dont F4 a besoin pour decider s'il route en priorite, et l'y mettre evite une
 * lecture a chaque message.
 *
 * <p><b>Le consommateur doit etre idempotent sur {@code leadId}.</b>
 */
public record QualifiedLeadMessage(
        UUID leadId,
        UUID clientId,
        int score,
        Instant qualifiedAt) {
}

package com.leadflow.crm;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.synced}. Fin de l'histoire du lead.
 *
 * <p>Aucun consommateur metier ne s'y abonne : la cle n'est liee qu'a la file du
 * monitoring. Le pipeline publie, qui ecoute ne le regarde pas — et aucun rejeu de cette
 * cle ne peut donc declencher quoi que ce soit.
 */
public record SyncedLeadMessage(
        UUID leadId, UUID clientId, String providerId, Instant syncedAt) {
}

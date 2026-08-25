package com.leadflow.tenant.dto;

import java.util.UUID;

/**
 * Reponse de creation — la seule, avec celle de rotation, a porter le secret en clair.
 *
 * <p>L'ecran l'affiche une fois puis l'oublie : le dashboard n'est pas un coffre a secrets
 * consultable, et le rendre a nouveau demanderait de le dechiffrer sur demande.
 */
public record ClientCreated(UUID id, String publicKey, String hmacSecret, String webhookPath) {
}

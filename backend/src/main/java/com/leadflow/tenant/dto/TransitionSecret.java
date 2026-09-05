package com.leadflow.tenant.dto;

import java.time.Instant;

/**
 * Etat de la fenetre pendant laquelle l'ancien secret d'une boutique reste accepte.
 *
 * <p>Aucun secret n'y figure, pas plus le precedent que le courant : la fiche n'en a jamais
 * rendu, et une transition n'est pas une raison de commencer.
 *
 * @param expireLe fin de la fenetre
 * @param dernierLeadAncienSecret date du dernier lead encore signe avec l'ancien secret,
 *     nulle quand il n'y en a aucun — c'est alors le feu vert pour revoquer
 */
public record TransitionSecret(Instant expireLe, Instant dernierLeadAncienSecret) {
}

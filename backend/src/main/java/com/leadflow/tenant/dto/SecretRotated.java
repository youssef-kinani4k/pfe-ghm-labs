package com.leadflow.tenant.dto;

import java.time.Instant;

/**
 * Reponse de rotation : le secret en clair, une derniere fois, et la fin de la fenetre
 * pendant laquelle l'ancien reste accepte.
 *
 * <p>Un record de deux champs plutot que la fiche complete : ce que l'ecran doit faire ici
 * est different — afficher, faire copier, dire jusqu'a quand l'ancien vaut, puis oublier.
 */
public record SecretRotated(String hmacSecret, Instant ancienSecretValideJusquA) {
}

package com.leadflow.tenant.dto;

/**
 * Reponse de rotation : le secret en clair, une derniere fois.
 *
 * <p>Un record d'un seul champ plutot que la fiche complete : ce que l'ecran doit faire ici
 * est different — afficher, faire copier, puis oublier.
 */
public record SecretRotated(String hmacSecret) {
}

package com.leadflow.common.auth;

import jakarta.validation.constraints.NotBlank;

/** Contrat de connexion. Les deux champs sont exiges avant meme d'interroger les comptes. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}

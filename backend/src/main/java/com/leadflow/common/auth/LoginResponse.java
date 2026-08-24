package com.leadflow.common.auth;

import java.time.Instant;

/**
 * {@code expiresAt} voyage avec le jeton pour que le frontend sache quand la session tombe
 * sans avoir a decoder le jeton lui-meme — un decodage cote client serait un parseur de
 * plus a maintenir, et sa validite ne prouverait rien de toute facon.
 */
public record LoginResponse(String token, Instant expiresAt) {
}

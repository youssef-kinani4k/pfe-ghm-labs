package com.leadflow.common.auth;

/** Refus de connexion, volontairement sans detail : voir le Javadoc d'AuthController. */
public class DashboardAuthenticationException extends RuntimeException {

    public DashboardAuthenticationException() {
        super("Identifiants invalides");
    }
}

package com.leadflow.common.auth;

import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seul endpoint ouvert du dashboard.
 *
 * <p>L'echec ne distingue pas identifiant inconnu et mot de passe faux : le distinguer
 * donnerait un oracle sur les comptes existants, exactement comme les cinq causes de refus
 * du webhook rendent toutes 401.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtIssuer jwtIssuer;

    public AuthController(AuthenticationManager authenticationManager, JwtIssuer jwtIssuer) {
        this.authenticationManager = authenticationManager;
        this.jwtIssuer = jwtIssuer;
    }

    @PostMapping("/login")
    public LoginResponse connexion(@Valid @RequestBody LoginRequest demande) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                    demande.username(), demande.password()));
        } catch (AuthenticationException echec) {
            throw new DashboardAuthenticationException();
        }
        return jwtIssuer.emet(demande.username());
    }
}

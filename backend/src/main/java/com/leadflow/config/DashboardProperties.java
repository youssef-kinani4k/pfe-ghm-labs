package com.leadflow.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Comptes du dashboard et parametres du jeton.
 *
 * <p>Les comptes vivent en configuration et non en base : ce sont deux ou trois operateurs
 * de l'agence provisionnes au deploiement, et F6 ne livre aucun ecran de gestion de comptes.
 * Une table dont le seul ecrivain serait un INSERT manuel n'apporterait rien de plus qu'une
 * variable d'environnement. Le jour ou chaque client aura son espace, seul le
 * {@code UserDetailsService} change.
 *
 * <p>Le mot de passe n'est jamais stocke en clair, meme ici : seul son hash BCrypt l'est.
 */
@ConfigurationProperties(prefix = "leadflow.dashboard")
public record DashboardProperties(
        String jwtSecret, Duration tokenTtl, List<Compte> users) {

    public record Compte(String username, String passwordHash) {}
}

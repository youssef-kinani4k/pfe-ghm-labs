package com.leadflow.common.auth;

import com.leadflow.config.DashboardProperties;
import java.util.List;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Comptes lus dans la configuration. Aucun role n'est attribue : le dashboard est une
 * console d'agence dont tous les operateurs sont equivalents, et introduire ADMIN/CLIENT
 * maintenant serait construire pour un besoin explicitement ecarte.
 *
 * <p>Sa presence retire l'utilisateur {@code user} a mot de passe aleatoire que Spring Boot
 * generait faute de {@code UserDetailsService} — une fermeture par accident, remplacee ici
 * par une fermeture par decision.
 */
@Service
public class DashboardUserDetailsService implements UserDetailsService {

    private final DashboardProperties properties;

    public DashboardUserDetailsService(DashboardProperties properties) {
        this.properties = properties;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        List<DashboardProperties.Compte> comptes =
                properties.users() == null ? List.of() : properties.users();

        return comptes.stream()
                .filter(compte -> compte.username() != null
                        && compte.username().equals(username))
                .findFirst()
                .map(compte -> User.withUsername(compte.username())
                        .password(compte.passwordHash())
                        .authorities("OPERATEUR")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Compte inconnu"));
    }
}

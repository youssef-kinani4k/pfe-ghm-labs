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
 *
 * <p>Le constructeur refuse de demarrer si aucun compte declare n'a de hash utilisable,
 * meme regle que {@code LEADFLOW_MASTER_KEY} depuis F1 et {@code LEADFLOW_JWT_SECRET}
 * depuis F6 : un hash vide laissait l'application demarrer et faisait echouer la premiere
 * connexion sans rien dire de la cause.
 */
@Service
public class DashboardUserDetailsService implements UserDetailsService {

    private final DashboardProperties properties;

    public DashboardUserDetailsService(DashboardProperties properties) {
        this.properties = properties;
        if (comptes().stream().noneMatch(compte -> compte.passwordHash() != null
                && !compte.passwordHash().isBlank())) {
            throw new IllegalStateException(
                    "Aucun compte operateur utilisable : le hash du mot de passe est vide. "
                            + "Definir LEADFLOW_ADMIN_PASSWORD_HASH avec un hash BCrypt, "
                            + "produit par : htpasswd -bnBC 10 \"\" 'mot-de-passe'");
        }
    }

    private List<DashboardProperties.Compte> comptes() {
        return properties.users() == null ? List.of() : properties.users();
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return comptes().stream()
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

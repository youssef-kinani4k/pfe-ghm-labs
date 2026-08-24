package com.leadflow.common.auth;

import com.leadflow.config.DashboardProperties;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emission du jeton de session du dashboard.
 *
 * <p>Signature symetrique HS256 : le seul verificateur est cette meme application, donc une
 * paire de cles asymetriques n'apporterait qu'une gestion de cles supplementaire.
 *
 * <p>Aucun jeton de rafraichissement. Un rafraichissement sans revocation ni stockage
 * n'ajoute que de la surface d'attaque : a l'expiration, retour a l'ecran de connexion.
 *
 * <p>Le jeton ne porte <b>aucun tenant</b>. Le dashboard voit tous les clients et le filtre
 * par client est un parametre de requete : mettre un {@code clientId} dans le jeton
 * figerait ici une decision qui appartient a l'ecran.
 */
@Component
public class JwtIssuer {

    private static final Duration TTL_PAR_DEFAUT = Duration.ofHours(8);

    private final JwtEncoder encoder;
    private final DashboardProperties properties;

    public JwtIssuer(JwtEncoder encoder, DashboardProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public LoginResponse emet(String username) {
        Instant maintenant = Instant.now();
        Duration duree = properties.tokenTtl() == null ? TTL_PAR_DEFAUT : properties.tokenTtl();
        Instant expiration = maintenant.plus(duree);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("leadflow")
                .issuedAt(maintenant)
                .expiresAt(expiration)
                .subject(username)
                .build();

        String jeton = encoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new LoginResponse(jeton, expiration);
    }
}

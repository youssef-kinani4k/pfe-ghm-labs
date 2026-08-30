package com.leadflow.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * L'API est sans session. Les webhooks s'authentifient par signature HMAC — verifiee dans
 * la couche capture, pas ici — et le dashboard par jeton Bearer.
 *
 * <p>La validation du jeton est confiee au support natif de Spring Security plutot qu'a un
 * filtre maison : aucun code de parsing chez nous, donc aucun bug d'expiration, d'algorithme
 * « none » ou de signature non verifiee.
 *
 * <p>{@code httpBasic} a disparu, et avec lui l'utilisateur genere a mot de passe aleatoire
 * que Spring Boot fabriquait faute de {@code UserDetailsService}.
 *
 * <p>Ne pas « securiser » {@code /api/webhooks/**} par un mecanisme Spring sans retirer la
 * verification HMAC, et inversement.
 *
 * <p>Les origines CORS viennent de {@code leadflow.security.cors.allowed-origins}. La liste
 * vide est le cas de production : derriere Nginx, le dashboard et l'API partagent une
 * origine unique et il n'y a plus de requete cross-origin a autoriser.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CorsProperties cors) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(configurateur -> {
                    List<String> origines = cors.origines();
                    if (origines.isEmpty()) {
                        // Liste vide = origine unique derriere Nginx. On ne desactive pas
                        // une protection : il n'y a rien a autoriser, donc rien a
                        // configurer, et un CORS configure a vide resterait un mecanisme
                        // actif dont il faudrait se demander ce qu'il fait des preflights.
                        configurateur.disable();
                    } else {
                        configurateur.configurationSource(source(origines));
                    }
                })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .exceptionHandling(gestion -> gestion
                        .authenticationEntryPoint((requete, reponse, echec) -> {
                            // ProblemDetail plutot que la page d'erreur par defaut : le
                            // frontend doit distinguer 401 (jeton expire -> connexion) de 403.
                            reponse.setStatus(HttpStatus.UNAUTHORIZED.value());
                            reponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            reponse.getWriter().write(
                                    "{\"type\":\"about:blank\",\"title\":\"Unauthorized\","
                                            + "\"status\":401,"
                                            + "\"detail\":\"Jeton absent ou invalide\"}");
                        }))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * La cle de signature vient de la configuration et n'a aucune valeur de repli, comme la
     * cle maitre du chiffrement : l'application doit refuser de demarrer plutot que de
     * signer avec un secret devinable.
     */
    private SecretKeySpec cle(DashboardProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "leadflow.dashboard.jwt-secret est vide : definir LEADFLOW_JWT_SECRET");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(DashboardProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(cle(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(DashboardProperties properties) {
        return NimbusJwtDecoder.withSecretKey(cle(properties)).build();
    }

    private CorsConfigurationSource source(List<String> origines) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origines);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

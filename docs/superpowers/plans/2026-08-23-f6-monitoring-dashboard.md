# F6 — Monitoring et dashboard : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendre le pipeline observable et pilotable — une API REST authentifiée, un journal des morts rejouable, un flux temps réel, et quatre écrans Angular.

**Architecture:** `monitoring/` est un **observateur** : il lit les tables des autres étapes par ses propres repositories en lecture seule, écoute une file AMQP qui lui est propre, et n'écrit que sa table `dead_letter`. L'authentification est un JWT HS256 validé nativement par Spring Security, avec des comptes déclarés en configuration. Le frontend Angular Material consomme des listes paginées côté serveur et un flux SSE lu par `fetch`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security (resource server), Spring AMQP, Spring Data JPA, Postgres, Flyway, JUnit 5 + AssertJ + Awaitility, Testcontainers. Angular 20 standalone + signals, Angular Material, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-23-f6-monitoring-dashboard-design.md`

## Global Constraints

- Les commandes backend s'exécutent depuis `backend/`, les commandes frontend depuis `frontend/`.
- **Une seule migration Flyway : `V4__dead_letter.sql`** (Task 7). Aucune autre tâche ne touche au schéma. Une tâche qui semble en réclamer une a dévié du plan.
- Code, commentaires et Javadoc **en français, sans accents**. Les documents Markdown, eux, portent leurs accents.
- Javadoc qui explique **pourquoi**, jamais quoi : chaque classe dit la décision qu'elle incarne et l'alternative écartée.
- Lignes ~100 colonnes, indentation 4 espaces.
- Les sous-packages sont des **étapes du pipeline**, pas des couches techniques : pas de `service/`, `repository/` ni `controller/` globaux.
- **Aucune entité JPA ne franchit la frontière HTTP.** `Client` porte `hmacSecret` et `crmConfig` déchiffrés à la lecture par les `AttributeConverter` : toute réponse passe par un `record` de `monitoring/dto/`.
- `monitoring/` **n'écrit jamais** dans `lead`, `raw_lead_event` ni `crm_sync_attempt`.
- Les corps JSON sont en **camelCase anglais** (`eventId`, `clientKey`), les erreurs en `ProblemDetail` (RFC 7807).
- Tous les tests de persistance utilisent `@SpringBootTest` + `TestcontainersConfiguration`, jamais `@DataJpaTest` : sa tranche n'inclut pas les `@Component` que sont les converters chiffrés.
- Le daemon Docker doit tourner pour `./mvnw test`.
- Commits en français, une tâche = un commit, message expliquant la décision et non le diff.
- Frontend : `environment.apiBaseUrl` est vide, les services appellent des **chemins relatifs** (`/api/leads`).

---

## Structure des fichiers

### Backend — créés

| Fichier | Responsabilité |
| --- | --- |
| `config/DashboardProperties.java` | Comptes opérateurs, secret JWT, durée du jeton |
| `config/MonitoringProperties.java` | Réglages des listeners, du flux et des filets |
| `common/auth/JwtIssuer.java` | Émission HS256 via `NimbusJwtEncoder` |
| `common/auth/DashboardUserDetailsService.java` | Comptes en configuration, hash BCrypt |
| `common/auth/AuthController.java` | `POST /api/auth/login` |
| `common/auth/LoginRequest.java` / `LoginResponse.java` | Contrats de connexion |
| `monitoring/dto/PageResponse.java` | Enveloppe de pagination, stable et à nous |
| `monitoring/dto/*.java` | `LeadSummary`, `LeadDetail`, `SalesRepView`, `SyncAttemptView`, `RawEventView`, `ClientSummary`, `SalesRepSummary`, `StatsView`, `ConnectorView`, `QueueView`, `DeadLetterView` |
| `monitoring/LeadQueryRepository.java` | `Repository` nu + `JpaSpecificationExecutor` |
| `monitoring/LeadSpecifications.java` | Un prédicat par filtre présent |
| `monitoring/LeadQueryService.java` | Page + résolution des noms sans `N+1` |
| `monitoring/LeadQueryController.java` | `GET /api/leads`, `GET /api/leads/{id}` |
| `monitoring/StatsService.java` / `StatsController.java` | Agrégats SQL, `GET /api/stats` |
| `monitoring/ClientDirectoryController.java` | `GET /api/clients`, `/api/clients/{id}/sales-reps` |
| `monitoring/ConnectorHealthController.java` | `GET /api/connectors`, dérivé des traces |
| `monitoring/QueueController.java` | `GET /api/queues` par `RabbitAdmin` |
| `monitoring/deadletter/DeadLetter.java` | Entité du journal |
| `monitoring/deadletter/DeadLetterStatus.java` | `PENDING` / `REPLAYED` / `DISCARDED` |
| `monitoring/deadletter/DeadLetterRepository.java` | Lecture + garde-fou des filets |
| `monitoring/deadletter/DeadLetterJournal.java` | Écriture en transaction propre |
| `monitoring/deadletter/DeadLetterListener.java` | Consommateur de `leadflow.leads.dlq` |
| `monitoring/deadletter/DeadLetterReplayService.java` | Republication à l'identique + marquage |
| `monitoring/deadletter/DeadLetterController.java` | `GET`/`POST /api/dead-letters` |
| `monitoring/stream/LeadStreamBroadcaster.java` | Registre d'émetteurs SSE |
| `monitoring/stream/LeadStreamController.java` | `GET /api/stream/leads` |
| `monitoring/stream/PipelineEventListener.java` | Consommateur de `leadflow.monitoring.events` |
| `monitoring/stream/StreamEvent.java` | Charge utile maigre du flux |
| `crm/SyncedLeadPublisher.java` | Publication `lead.synced` |
| `qualification/QualifiedLeadRelay.java` | Filet de republication de `lead.qualified` |
| `routing/RoutedLeadRelay.java` | Filet de republication de `lead.routed` |
| `db/migration/V4__dead_letter.sql` | La table du journal |

### Backend — modifiés

`config/SecurityConfig.java`, `config/RabbitMQConfig.java`, `common/ApiExceptionHandler.java`,
`qualification/LeadRepository.java`, `application.yml`, `src/test/resources/application.properties`, `pom.xml`.

### Frontend — créés

`core/auth/` (`auth.ts`, `auth-interceptor.ts`, `auth-guard.ts`), `core/api/` (six services),
`core/models/` (interfaces), `core/stream/lead-stream.ts`, `shared/status-badge/`,
`features/login/`, `features/leads/lead-detail/`. Les quatre features existantes sont remplies.

---

## Ordre et dépendances

```
T1 auth ──> T2 leads ──> T3 detail ──> T4 annuaire ──> T5 stats ──> T6 connecteurs
                                                                          │
T7 migration ──> T8 journal ──> T9 rejeu ──> T10 files ──> T11 flux ──> T12 filets
                                                                          │
                                        T13 socle front ──> T14 leads front
                                        ──> T15 dashboard front ──> T16 queue+connecteurs front
                                                                          │
                                                                    T17 documentation
```

T1 précède tout le reste du backend : sans authentification, chaque test d'endpoint écrit
ensuite devrait être réécrit. T7 précède T8. T13 précède les autres tâches frontend.

---

## Task 1: Authentification JWT

**Files:**
- Create: `backend/src/main/java/com/leadflow/config/DashboardProperties.java`
- Create: `backend/src/main/java/com/leadflow/common/auth/JwtIssuer.java`
- Create: `backend/src/main/java/com/leadflow/common/auth/DashboardUserDetailsService.java`
- Create: `backend/src/main/java/com/leadflow/common/auth/AuthController.java`
- Create: `backend/src/main/java/com/leadflow/common/auth/LoginRequest.java`
- Create: `backend/src/main/java/com/leadflow/common/auth/LoginResponse.java`
- Modify: `backend/src/main/java/com/leadflow/config/SecurityConfig.java`
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/leadflow/common/auth/AuthenticationTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: `JwtIssuer.emet(String username) -> LoginResponse(String token, Instant expiresAt)` ;
  toutes les tâches suivantes s'appuient sur le fait qu'un endpoint non listé en `permitAll`
  exige `Authorization: Bearer <token>`. Le nom de l'opérateur est lisible par
  `SecurityContextHolder.getContext().getAuthentication().getName()`.

- [ ] **Step 1: Ajouter la dépendance resource server**

Le nom des starters a changé sous Boot 4.1 (`spring-boot-starter-webmvc`, pas `-web`).
Vérifier le nom réel avant d'écrire :

```bash
cd backend && ./mvnw -q dependency:list -DincludeGroupIds=org.springframework.boot 2>&1 | grep -i oauth2
```

Ajouter dans `pom.xml`, à côté de `spring-boot-starter-security` :

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
```

Si la résolution échoue, retomber sur `spring-boot-starter-oauth2-resource-server` — c'est le
nom sous Boot 3. Vérifier par `./mvnw -q dependency:resolve` avant d'aller plus loin.

- [ ] **Step 2: Écrire le test qui échoue**

`backend/src/test/java/com/leadflow/common/auth/AuthenticationTest.java` :

```java
package com.leadflow.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Le hash correspond au mot de passe « secret-de-test ». Il est ecrit en clair dans le
 * test et nulle part ailleurs : la configuration de production ne porte que des hash,
 * venus de l'environnement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.jwt-secret=cle-de-signature-de-test-suffisamment-longue-32o",
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"})
class AuthenticationTest {

    @Autowired private TestRestTemplate rest;

    @Test
    void connexionValideRendUnJeton() {
        ResponseEntity<Map> reponse = rest.postForEntity(
                "/api/auth/login",
                Map.of("username", "operateur", "password", "secret-de-test"),
                Map.class);

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reponse.getBody()).containsKeys("token", "expiresAt");
    }

    @Test
    void motDePasseFauxEstRefuse() {
        ResponseEntity<Map> reponse = rest.postForEntity(
                "/api/auth/login",
                Map.of("username", "operateur", "password", "pas-le-bon"),
                Map.class);

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void endpointProtegeSansJetonEstRefuse() {
        assertThat(rest.getForEntity("/api/clients", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void jetonValideOuvreLAcces() {
        String jeton = (String) rest.postForEntity(
                        "/api/auth/login",
                        Map.of("username", "operateur", "password", "secret-de-test"),
                        Map.class)
                .getBody()
                .get("token");

        HttpHeaders entetes = new HttpHeaders();
        entetes.setBearerAuth(jeton);

        // /api/clients n'existe pas encore : ce qui est verifie ici est que le jeton passe
        // le filtre de securite, donc que la reponse n'est plus un 401.
        ResponseEntity<String> reponse = rest.exchange(
                "/api/clients", HttpMethod.GET, new HttpEntity<>(entetes), String.class);

        assertThat(reponse.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void webhookResteAccessibleSansJeton() {
        // Sans signature le webhook rend 401, mais par la verification HMAC et non par la
        // chaine de filtres : la preuve est qu'il ne rend pas 403 et qu'il atteint bien le
        // controleur. On verifie ici qu'il n'est pas devenu inaccessible.
        ResponseEntity<String> reponse =
                rest.postForEntity("/api/webhooks/leads/inconnue", "{}", String.class);

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 3: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=AuthenticationTest`
Expected: FAIL — `/api/auth/login` renvoie 404, et la connexion n'existe pas.

- [ ] **Step 4: Écrire `DashboardProperties`**

```java
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
 * UserDetailsService change.
 *
 * <p>Le mot de passe n'est jamais stocke en clair, meme ici : seul son hash BCrypt l'est.
 */
@ConfigurationProperties(prefix = "leadflow.dashboard")
public record DashboardProperties(
        String jwtSecret, Duration tokenTtl, List<Compte> users) {

    public record Compte(String username, String passwordHash) {}
}
```

- [ ] **Step 5: Écrire `DashboardUserDetailsService`, `JwtIssuer` et les contrats**

`LoginRequest.java` :

```java
package com.leadflow.common.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}
```

`LoginResponse.java` :

```java
package com.leadflow.common.auth;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt) {
}
```

`DashboardUserDetailsService.java` :

```java
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
                .filter(compte -> compte.username().equals(username))
                .findFirst()
                .map(compte -> User.withUsername(compte.username())
                        .password(compte.passwordHash())
                        .authorities("OPERATEUR")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Compte inconnu"));
    }
}
```

`JwtIssuer.java` :

```java
package com.leadflow.common.auth;

import com.leadflow.config.DashboardProperties;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Component;

/**
 * Emission du jeton de session du dashboard.
 *
 * <p>Signature symetrique HS256 : le seul verificateur est cette meme application, donc une
 * paire de cles asymetriques n'apporterait qu'une gestion de cles supplementaire.
 *
 * <p>Aucun jeton de rafraichissement. Un rafraichissement sans revocation ni stockage
 * n'ajoute que de la surface d'attaque : a l'expiration, retour a l'ecran de connexion.
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
```

- [ ] **Step 6: Écrire `AuthController`**

```java
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
```

Et l'exception, `common/auth/DashboardAuthenticationException.java` :

```java
package com.leadflow.common.auth;

/** Refus de connexion, volontairement sans detail : voir le Javadoc d'AuthController. */
public class DashboardAuthenticationException extends RuntimeException {

    public DashboardAuthenticationException() {
        super("Identifiants invalides");
    }
}
```

- [ ] **Step 7: Réécrire `SecurityConfig`**

```java
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
 * <p>Ne pas « securiser » /api/webhooks/** par un mecanisme Spring sans retirer la
 * verification HMAC, et inversement.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
                                            + "\"status\":401,\"detail\":\"Jeton absent ou invalide\"}");
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

    private SecretKeySpec cle(DashboardProperties properties) {
        return new SecretKeySpec(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(DashboardProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(cle(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(DashboardProperties properties) {
        return NimbusJwtDecoder.withSecretKey(cle(properties)).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // A elargir avant tout deploiement : F7.
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

- [ ] **Step 8: Traduire l'échec de connexion en 401**

Ajouter dans `common/ApiExceptionHandler.java` :

```java
    /** Meme reponse pour un identifiant inconnu et un mot de passe faux (cf. AuthController). */
    @ExceptionHandler(DashboardAuthenticationException.class)
    ProblemDetail refusDeConnexion(DashboardAuthenticationException echec) {
        log.warn("Connexion au dashboard refusee");
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
    }
```

- [ ] **Step 9: Compléter la configuration**

Dans `application.yml`, sous `leadflow:` :

```yaml
  dashboard:
    # Cle de signature du jeton de session. Aucune valeur de repli, comme la cle maitre :
    # l'application doit refuser de demarrer plutot que de signer avec un secret devinable.
    jwt-secret: ${LEADFLOW_JWT_SECRET:}
    token-ttl: 8h
    users:
      - username: ${LEADFLOW_ADMIN_USER:admin}
        # Hash BCrypt, jamais le mot de passe. Genere par exemple avec htpasswd -bnBC 10 "" motdepasse
        password-hash: ${LEADFLOW_ADMIN_PASSWORD_HASH:}
```

Dans `src/test/resources/application.properties` :

```properties
# Secret de signature fixe pour la suite de tests, comme la cle maitre au-dessus.
leadflow.dashboard.jwt-secret=cle-de-signature-de-test-suffisamment-longue-32o
```

- [ ] **Step 10: Vérifier que le test passe**

Run: `cd backend && ./mvnw test -Dtest=AuthenticationTest`
Expected: PASS, les cinq méthodes.

- [ ] **Step 11: Vérifier que rien d'existant n'a cassé**

Run: `cd backend && ./mvnw test`
Expected: PASS. Les tests de capture appellent `/api/webhooks/**`, resté en `permitAll`.

- [ ] **Step 12: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/leadflow/common/auth \
        backend/src/main/java/com/leadflow/config/DashboardProperties.java \
        backend/src/main/java/com/leadflow/config/SecurityConfig.java \
        backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java \
        backend/src/main/resources/application.yml \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/leadflow/common/auth
git commit -m "feat: authentification du dashboard par jeton JWT

Le httpBasic sans UserDetailsService laissait Spring Boot generer un compte
user a mot de passe aleatoire : une fermeture par accident, pas par decision.
Les comptes vivent en configuration, la validation du jeton est celle de
Spring Security — aucun filtre maison, donc aucun bug de parsing."
```

---

## Task 2: Liste des leads paginée et filtrée

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/PageResponse.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/LeadSummary.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadQueryRepository.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadSpecifications.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadQueryService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadQueryController.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadQueryServiceTest.java`

**Interfaces:**
- Consumes: la chaîne de sécurité de Task 1.
- Produces: `PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)`,
  `LeadSummary`, et `LeadQueryService.cherche(LeadFilter, Pageable) -> PageResponse<LeadSummary>`.
  Task 3 réutilise `LeadQueryRepository` ; T13+ consomment `PageResponse` en TypeScript.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadQueryServiceTest {

    @Autowired private LeadQueryService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    private UUID clientId;
    private UUID commercialId;

    @BeforeEach
    void jeuDeDonnees() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Nord");
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost"));
        clientId = clientRepository.saveAndFlush(client).getId();

        SalesRep commercial = new SalesRep();
        commercial.setClientId(clientId);
        commercial.setFullName("Sonia Berger");
        commercial.setEmail("sonia@exemple.fr");
        commercialId = salesRepRepository.saveAndFlush(commercial).getId();

        creeLead("chaud@exemple.fr", LeadStatus.SYNCED, 80, commercialId);
        creeLead("tiede@exemple.fr", LeadStatus.QUALIFIED, 40, null);
        creeLead("froid@exemple.fr", LeadStatus.REJECTED, 10, null);
    }

    private void creeLead(String email, LeadStatus statut, int score, UUID commercial) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(Map.of("email", email));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(java.time.Instant.now());
        rawLeadEventRepository.saveAndFlush(evenement);

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(evenement.getId());
        lead.setEmail(email);
        lead.setCompanyName("Ets " + email);
        lead.setScore(score);
        lead.setStatus(statut);
        lead.setAssignedSalesRepId(commercial);
        leadRepository.saveAndFlush(lead);
    }

    @AfterEach
    void nettoyage() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void rendTousLesLeadsDuClientSansFiltre() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecClientId(clientId), PageRequest.of(0, 25));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactlyInAnyOrder(
                        "chaud@exemple.fr", "tiede@exemple.fr", "froid@exemple.fr");
    }

    @Test
    void filtreParStatut() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecStatuts(List.of(LeadStatus.SYNCED)),
                PageRequest.of(0, 25));

        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactly("chaud@exemple.fr");
    }

    @Test
    void combineScoreMinimalEtRecherche() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecMinScore(30).avecRecherche("tiede"),
                PageRequest.of(0, 25));

        assertThat(page.content()).extracting(LeadSummary::email)
                .containsExactly("tiede@exemple.fr");
    }

    @Test
    void resoutLesNomsDeClientEtDeCommercial() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecStatuts(List.of(LeadStatus.SYNCED)),
                PageRequest.of(0, 25));

        LeadSummary ligne = page.content().getFirst();
        assertThat(ligne.clientName()).isEqualTo("Agence Nord");
        assertThat(ligne.salesRepName()).isEqualTo("Sonia Berger");
    }

    @Test
    void pagineEtPlafonneLaTaille() {
        PageResponse<LeadSummary> page = service.cherche(
                LeadFilter.vide().avecClientId(clientId),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=LeadQueryServiceTest`
Expected: FAIL — compilation impossible, `LeadQueryService` et `LeadFilter` n'existent pas.

- [ ] **Step 3: Écrire `PageResponse` et `LeadSummary`**

```java
package com.leadflow.monitoring.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Enveloppe de pagination du projet.
 *
 * <p>La serialisation directe d'un {@code Page} Spring est instable d'une version a l'autre
 * — Spring Boot lui-meme en avertit — et le frontend en dependrait. Ce record est un contrat
 * qui nous appartient, ecrit une fois pour toutes les listes.
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> de(Page<S> page, List<T> contenu) {
        return new PageResponse<>(
                contenu, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
```

```java
package com.leadflow.monitoring.dto;

import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.UUID;

/** Ligne de la liste des leads. Jamais l'entite : voir la regle 3.4 de la spec. */
public record LeadSummary(
        UUID id,
        Instant createdAt,
        UUID clientId,
        String clientName,
        String companyName,
        String email,
        String detectedIntent,
        IntentSource intentSource,
        int score,
        LeadStatus status,
        UUID assignedSalesRepId,
        String salesRepName,
        String countryCode,
        String sector) {
}
```

- [ ] **Step 4: Écrire `LeadFilter`, `LeadQueryRepository` et `LeadSpecifications`**

`monitoring/LeadFilter.java` :

```java
package com.leadflow.monitoring;

import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Criteres de recherche, tous facultatifs. Record avec fabriques « avec... » plutot qu'une
 * dizaine de parametres de methode : les appels du controleur restent lisibles et les tests
 * ne construisent que ce qu'ils eprouvent.
 */
public record LeadFilter(
        UUID clientId,
        List<LeadStatus> statuts,
        String intent,
        IntentSource intentSource,
        UUID salesRepId,
        Integer minScore,
        Instant from,
        Instant to,
        String recherche) {

    public static LeadFilter vide() {
        return new LeadFilter(null, null, null, null, null, null, null, null, null);
    }

    public LeadFilter avecClientId(UUID valeur) {
        return new LeadFilter(valeur, statuts, intent, intentSource, salesRepId, minScore,
                from, to, recherche);
    }

    public LeadFilter avecStatuts(List<LeadStatus> valeur) {
        return new LeadFilter(clientId, valeur, intent, intentSource, salesRepId, minScore,
                from, to, recherche);
    }

    public LeadFilter avecMinScore(Integer valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, valeur,
                from, to, recherche);
    }

    public LeadFilter avecRecherche(String valeur) {
        return new LeadFilter(clientId, statuts, intent, intentSource, salesRepId, minScore,
                from, to, valeur);
    }
}
```

`monitoring/LeadQueryRepository.java` :

```java
package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * Repository propre au monitoring.
 *
 * <p>{@code LeadRepository} appartient a la qualification et porte les requetes de la
 * deduplication et du tour de role ; y ajouter {@code JpaSpecificationExecutor} pour les
 * besoins d'un ecran melangerait deux responsabilites dans une interface que F3 et F4
 * lisent deja.
 *
 * <p>{@code Repository} nu et non {@code JpaRepository} : aucune methode d'ecriture n'est
 * meme exposee, ce qui rend la regle « le monitoring n'ecrit pas » verifiable a la lecture.
 */
public interface LeadQueryRepository
        extends Repository<Lead, UUID>, JpaSpecificationExecutor<Lead> {
}
```

`monitoring/LeadSpecifications.java` :

```java
package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/** Un predicat par filtre present, et rien pour les filtres absents. */
final class LeadSpecifications {

    private LeadSpecifications() {}

    static Specification<Lead> depuis(LeadFilter filtre) {
        return (racine, requete, constructeur) -> {
            List<Predicate> predicats = new ArrayList<>();

            if (filtre.clientId() != null) {
                predicats.add(constructeur.equal(racine.get("clientId"), filtre.clientId()));
            }
            if (filtre.statuts() != null && !filtre.statuts().isEmpty()) {
                predicats.add(racine.get("status").in(filtre.statuts()));
            }
            if (filtre.intent() != null) {
                predicats.add(constructeur.equal(racine.get("detectedIntent"), filtre.intent()));
            }
            if (filtre.intentSource() != null) {
                predicats.add(
                        constructeur.equal(racine.get("intentSource"), filtre.intentSource()));
            }
            if (filtre.salesRepId() != null) {
                predicats.add(
                        constructeur.equal(racine.get("assignedSalesRepId"), filtre.salesRepId()));
            }
            if (filtre.minScore() != null) {
                predicats.add(
                        constructeur.greaterThanOrEqualTo(racine.get("score"), filtre.minScore()));
            }
            if (filtre.from() != null) {
                predicats.add(
                        constructeur.greaterThanOrEqualTo(racine.get("createdAt"), filtre.from()));
            }
            if (filtre.to() != null) {
                predicats.add(constructeur.lessThan(racine.get("createdAt"), filtre.to()));
            }
            if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
                String motif = "%" + filtre.recherche().toLowerCase() + "%";
                predicats.add(constructeur.or(
                        constructeur.like(constructeur.lower(racine.get("email")), motif),
                        constructeur.like(
                                constructeur.lower(racine.get("companyName")), motif)));
            }
            return constructeur.and(predicats.toArray(Predicate[]::new));
        };
    }
}
```

- [ ] **Step 5: Écrire `LeadQueryService`**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.Lead;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture des leads pour le dashboard.
 *
 * <p>{@code Lead} ne porte pas d'association JPA vers {@code client} ni {@code sales_rep} —
 * choix de F1 qui garde les etapes decouplees — donc aucun {@code join fetch} n'est possible.
 * Les noms sont resolus en deux requetes supplementaires par page, bornees par la taille de
 * la page : trois requetes au total plutot qu'un N+1, et surtout aucune association ajoutee
 * aux entites du pipeline pour le confort d'un ecran.
 *
 * <p>{@code open-in-view} est a false : la conversion en DTO se fait ici, sous transaction.
 */
@Service
public class LeadQueryService {

    private final LeadQueryRepository leads;
    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;

    public LeadQueryService(
            LeadQueryRepository leads,
            ClientRepository clients,
            SalesRepRepository commerciaux) {
        this.leads = leads;
        this.clients = clients;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadSummary> cherche(LeadFilter filtre, Pageable pagination) {
        Page<Lead> page = leads.findAll(LeadSpecifications.depuis(filtre), pagination);

        Map<UUID, String> nomsDeClient = nomsDeClient(page.getContent());
        Map<UUID, String> nomsDeCommercial = nomsDeCommercial(page.getContent());

        List<LeadSummary> lignes = page.getContent().stream()
                .map(lead -> new LeadSummary(
                        lead.getId(),
                        lead.getCreatedAt(),
                        lead.getClientId(),
                        nomsDeClient.get(lead.getClientId()),
                        lead.getCompanyName(),
                        lead.getEmail(),
                        lead.getDetectedIntent(),
                        lead.getIntentSource(),
                        lead.getScore(),
                        lead.getStatus(),
                        lead.getAssignedSalesRepId(),
                        nomsDeCommercial.get(lead.getAssignedSalesRepId()),
                        lead.getCountryCode(),
                        lead.getSector()))
                .toList();

        return PageResponse.de(page, lignes);
    }

    private Map<UUID, String> nomsDeClient(List<Lead> page) {
        Set<UUID> identifiants =
                page.stream().map(Lead::getClientId).collect(Collectors.toSet());
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return clients.findAllById(identifiants).stream()
                .collect(Collectors.toMap(Client::getId, Client::getName));
    }

    private Map<UUID, String> nomsDeCommercial(List<Lead> page) {
        Set<UUID> identifiants = page.stream()
                .map(Lead::getAssignedSalesRepId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return commerciaux.findAllById(identifiants).stream()
                .collect(Collectors.toMap(SalesRep::getId, Function.identity()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFullName()));
    }
}
```

- [ ] **Step 6: Vérifier que le test passe**

Run: `cd backend && ./mvnw test -Dtest=LeadQueryServiceTest`
Expected: PASS, les cinq méthodes.

- [ ] **Step 7: Écrire le contrôleur**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lecture seule. Le tenant est un filtre de requete, jamais une donnee portee par le jeton. */
@RestController
@RequestMapping("/api/leads")
public class LeadQueryController {

    /** Plafond de page : une liste d'ecran ne demande jamais mille lignes d'un coup. */
    private static final int TAILLE_MAXIMALE = 100;

    private final LeadQueryService service;

    public LeadQueryController(LeadQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<LeadSummary> liste(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) List<LeadStatus> status,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) IntentSource intentSource,
            @RequestParam(required = false) UUID salesRepId,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String q,
            Pageable pagination) {

        LeadFilter filtre = new LeadFilter(
                clientId, status, intent, intentSource, salesRepId, minScore, from, to, q);
        return service.cherche(filtre, plafonne(pagination));
    }

    private Pageable plafonne(Pageable demande) {
        if (demande.getPageSize() <= TAILLE_MAXIMALE) {
            return demande;
        }
        // Plafonne en silence plutot qu'en erreur : une taille excessive est une maladresse
        // d'appelant, pas une faute qui merite de faire echouer l'ecran.
        return org.springframework.data.domain.PageRequest.of(
                demande.getPageNumber(), TAILLE_MAXIMALE, demande.getSort());
    }
}
```

- [ ] **Step 8: Lancer la suite complète et committer**

Run: `cd backend && ./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring backend/src/test/java/com/leadflow/monitoring
git commit -m "feat: liste des leads paginee et filtree

Repository propre au monitoring plutot qu'un JpaSpecificationExecutor greffe
sur LeadRepository, que F3 et F4 lisent deja. Les noms de client et de
commercial sont resolus en deux requetes bornees par page : les entites du
pipeline ne gagnent aucune association pour le confort d'un ecran."
```

---

## Task 3: Détail d'un lead

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/LeadDetail.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/SalesRepView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/SyncAttemptView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/RawEventView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/LeadDetailService.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadQueryController.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/LeadQueryRepository.java`
- Modify: `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/RessourceIntrouvableException.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/LeadDetailServiceTest.java`

**Interfaces:**
- Consumes: `LeadQueryRepository` (T2), `CrmSyncAttemptRepository` (F5), `RawLeadEventRepository` (F2).
- Produces: `LeadDetailService.detail(UUID) -> LeadDetail` ; `RessourceIntrouvableException` réutilisée par T9.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.LeadDetail;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadDetailServiceTest {

    @Autowired private LeadDetailService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private CrmSyncAttemptRepository attemptRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @AfterEach
    void nettoyage() {
        attemptRepository.deleteAll();
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void rendLeLeadSonCommercialSesTentativesEtSonEvenementBrut() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Agence Sud");
        client.setHmacSecret("secret");
        client.setCrmProviderId("odoo");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost"));
        UUID clientId = clientRepository.saveAndFlush(client).getId();

        SalesRep commercial = new SalesRep();
        commercial.setClientId(clientId);
        commercial.setFullName("Karim Daoud");
        commercial.setEmail("karim@exemple.fr");
        commercial.setSector("industrie");
        UUID commercialId = salesRepRepository.saveAndFlush(commercial).getId();

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-contact");
        evenement.setPayload(Map.of("email", "prospect@exemple.fr", "tel", "0102030405"));
        evenement.setSignature("sig-" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now());
        rawLeadEventRepository.saveAndFlush(evenement);

        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(evenement.getId());
        lead.setEmail("prospect@exemple.fr");
        lead.setPhone("0102030405");
        lead.setMessage("Besoin d'un devis");
        lead.setScore(70);
        lead.setStatus(LeadStatus.SYNCED);
        lead.setAssignedSalesRepId(commercialId);
        UUID leadId = leadRepository.saveAndFlush(lead).getId();

        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId("odoo");
        tentative.setStatus(CrmSyncAttemptStatus.SUCCESS);
        tentative.setAccountRef("42");
        tentative.setAttemptedAt(Instant.now());
        attemptRepository.saveAndFlush(tentative);

        LeadDetail detail = service.detail(leadId);

        assertThat(detail.email()).isEqualTo("prospect@exemple.fr");
        assertThat(detail.message()).isEqualTo("Besoin d'un devis");
        assertThat(detail.salesRep().fullName()).isEqualTo("Karim Daoud");
        assertThat(detail.syncAttempts()).hasSize(1);
        assertThat(detail.syncAttempts().getFirst().accountRef()).isEqualTo("42");
        assertThat(detail.rawEvent().source()).isEqualTo("formulaire-contact");
        assertThat(detail.rawEvent().payload()).containsEntry("tel", "0102030405");
    }

    @Test
    void identifiantInconnuLeveUneRessourceIntrouvable() {
        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOf(RessourceIntrouvableException.class);
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=LeadDetailServiceTest`
Expected: FAIL — `LeadDetailService` n'existe pas.

- [ ] **Step 3: Écrire les DTO**

```java
package com.leadflow.monitoring.dto;

import java.util.UUID;

public record SalesRepView(
        UUID id, String fullName, String email, String sector, String zone, String crmRef) {
}
```

```java
package com.leadflow.monitoring.dto;

import com.leadflow.crm.CrmSyncAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record SyncAttemptView(
        UUID id,
        String providerId,
        CrmSyncAttemptStatus status,
        String accountRef,
        String contactRef,
        String opportunityRef,
        String taskRef,
        String errorMessage,
        Instant attemptedAt) {
}
```

```java
package com.leadflow.monitoring.dto;

import com.leadflow.capture.RawLeadEventStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * La charge utile brute est incluse deliberement : c'est l'ecran ou l'on repond a « pourquoi
 * ce lead n'a pas de telephone » ou « pourquoi cet evenement est DISCARDED », et sans elle la
 * reponse demande un acces psql. Elle ne contient aucun secret — ceux-ci sont sur la ligne
 * client, chiffres, et ne sortent jamais.
 */
public record RawEventView(
        UUID id,
        String source,
        Instant receivedAt,
        RawLeadEventStatus status,
        String failureReason,
        Map<String, Object> payload) {
}
```

```java
package com.leadflow.monitoring.dto;

import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LeadDetail(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        UUID clientId,
        String clientName,
        String companyName,
        String firstName,
        String lastName,
        String email,
        String phone,
        String message,
        String detectedIntent,
        IntentSource intentSource,
        int score,
        LeadStatus status,
        String countryCode,
        String sector,
        SalesRepView salesRep,
        List<SyncAttemptView> syncAttempts,
        RawEventView rawEvent) {
}
```

- [ ] **Step 4: Compléter le repository et écrire l'exception**

Ajouter à `LeadQueryRepository` :

```java
    java.util.Optional<Lead> findById(UUID id);
```

`monitoring/RessourceIntrouvableException.java` :

```java
package com.leadflow.monitoring;

/** Traduite en 404 par ApiExceptionHandler. */
public class RessourceIntrouvableException extends RuntimeException {

    public RessourceIntrouvableException(String message) {
        super(message);
    }
}
```

Et dans `common/ApiExceptionHandler.java` :

```java
    @ExceptionHandler(RessourceIntrouvableException.class)
    ProblemDetail ressourceIntrouvable(RessourceIntrouvableException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, echec.getMessage());
    }
```

Vérifier que `CrmSyncAttemptRepository` expose la lecture par lead ; sinon ajouter :

```java
    java.util.List<CrmSyncAttempt> findByLeadIdOrderByAttemptedAtDesc(java.util.UUID leadId);
```

- [ ] **Step 5: Écrire `LeadDetailService`**

```java
package com.leadflow.monitoring;

import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.monitoring.dto.LeadDetail;
import com.leadflow.monitoring.dto.RawEventView;
import com.leadflow.monitoring.dto.SalesRepView;
import com.leadflow.monitoring.dto.SyncAttemptView;
import com.leadflow.qualification.Lead;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assemble le detail d'un lead a partir des traces des trois etapes qui l'ont touche. */
@Service
public class LeadDetailService {

    private final LeadQueryRepository leads;
    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;
    private final CrmSyncAttemptRepository tentatives;
    private final RawLeadEventRepository evenements;

    public LeadDetailService(
            LeadQueryRepository leads,
            ClientRepository clients,
            SalesRepRepository commerciaux,
            CrmSyncAttemptRepository tentatives,
            RawLeadEventRepository evenements) {
        this.leads = leads;
        this.clients = clients;
        this.commerciaux = commerciaux;
        this.tentatives = tentatives;
        this.evenements = evenements;
    }

    @Transactional(readOnly = true)
    public LeadDetail detail(UUID leadId) {
        Lead lead = leads.findById(leadId).orElseThrow(
                () -> new RessourceIntrouvableException("Lead inconnu : " + leadId));

        String nomDuClient = clients.findById(lead.getClientId())
                .map(com.leadflow.tenant.Client::getName)
                .orElse(null);

        SalesRepView commercial = lead.getAssignedSalesRepId() == null
                ? null
                : commerciaux.findById(lead.getAssignedSalesRepId())
                        .map(rep -> new SalesRepView(
                                rep.getId(), rep.getFullName(), rep.getEmail(),
                                rep.getSector(), rep.getZone(), rep.getCrmRef()))
                        .orElse(null);

        List<SyncAttemptView> historique =
                tentatives.findByLeadIdOrderByAttemptedAtDesc(leadId).stream()
                        .map(t -> new SyncAttemptView(
                                t.getId(), t.getProviderId(), t.getStatus(), t.getAccountRef(),
                                t.getContactRef(), t.getOpportunityRef(), t.getTaskRef(),
                                t.getErrorMessage(), t.getAttemptedAt()))
                        .toList();

        RawEventView brut = evenements.findById(lead.getRawEventId())
                .map(this::vue)
                .orElse(null);

        return new LeadDetail(
                lead.getId(), lead.getCreatedAt(), lead.getUpdatedAt(), lead.getClientId(),
                nomDuClient, lead.getCompanyName(), lead.getFirstName(), lead.getLastName(),
                lead.getEmail(), lead.getPhone(), lead.getMessage(), lead.getDetectedIntent(),
                lead.getIntentSource(), lead.getScore(), lead.getStatus(),
                lead.getCountryCode(), lead.getSector(), commercial, historique, brut);
    }

    private RawEventView vue(RawLeadEvent evenement) {
        return new RawEventView(
                evenement.getId(), evenement.getSource(), evenement.getReceivedAt(),
                evenement.getStatus(), evenement.getFailureReason(), evenement.getPayload());
    }
}
```

- [ ] **Step 6: Brancher l'endpoint**

Ajouter à `LeadQueryController` le champ `LeadDetailService` dans le constructeur, puis :

```java
    @GetMapping("/{id}")
    public LeadDetail detail(@PathVariable UUID id) {
        return detailService.detail(id);
    }
```

- [ ] **Step 7: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=LeadDetailServiceTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java \
        backend/src/main/java/com/leadflow/crm/CrmSyncAttemptRepository.java \
        backend/src/test/java/com/leadflow/monitoring/LeadDetailServiceTest.java
git commit -m "feat: detail d'un lead avec son historique de synchronisation

La charge utile brute de l'evenement est rendue : c'est l'ecran ou l'on
explique pourquoi un champ manque, et sans elle la reponse demande un acces
psql. Aucun secret n'y transite, ceux-ci vivant chiffres sur la ligne client."
```

---

## Task 4: Annuaire des clients et des commerciaux

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/ClientSummary.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/SalesRepSummary.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/ClientDirectoryController.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/ClientDirectoryService.java`
- Modify: `backend/src/main/java/com/leadflow/tenant/SalesRepRepository.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/ClientDirectoryTest.java`

**Interfaces:**
- Consumes: `ClientRepository`, `SalesRepRepository` (F1), l'authentification de T1.
- Produces: `GET /api/clients`, `GET /api/clients/{id}/sales-reps`. Alimente les listes
  déroulantes de filtre de T14 et T15.

C'est la tâche qui porte le **test de non-fuite de secret** du critère de recette 5.
`Client` porte `hmacSecret` et `crmConfig` que les `AttributeConverter` **déchiffrent à la
lecture** : sérialiser l'entité publierait le secret HMAC sur HTTP. L'assertion porte donc
sur le **corps JSON**, pas sur le DTO — c'est la sérialisation qu'on verrouille, et un DTO
correct ne prouve rien si un jour quelqu'un renvoie l'entité.

- [ ] **Step 1: Écrire le test qui échoue**

`backend/src/test/java/com/leadflow/monitoring/ClientDirectoryTest.java` :

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "leadflow.dashboard.jwt-secret=cle-de-signature-de-test-suffisamment-longue-32o",
        "leadflow.dashboard.users[0].username=operateur",
        "leadflow.dashboard.users[0].password-hash="
                + "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"})
class ClientDirectoryTest {

    private static final String SECRET_EN_CLAIR = "secret-hmac-tres-reconnaissable";

    @Autowired private TestRestTemplate rest;
    @Autowired private ClientRepository clientRepository;
    @Autowired private SalesRepRepository salesRepRepository;

    @AfterEach
    void nettoie() {
        salesRepRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void listeLesClientsSansAucunSecret() {
        creeUnClientAvecUnCommercial();

        ResponseEntity<String> reponse = appelAuthentifie("/api/clients");

        assertThat(reponse.getStatusCode().is2xxSuccessful()).isTrue();
        // Assertion sur le corps brut, pas sur le DTO : c'est la serialisation qu'on
        // verrouille. Un DTO correct ne prouve rien si l'entite revient un jour.
        assertThat(reponse.getBody())
                .doesNotContain(SECRET_EN_CLAIR)
                .doesNotContain("hmacSecret")
                .doesNotContain("crmConfig")
                .doesNotContain("scoringConfig")
                .contains("Client de test");
    }

    @Test
    void listeLesCommerciauxDUnClient() {
        Client client = creeUnClientAvecUnCommercial();

        ResponseEntity<String> reponse =
                appelAuthentifie("/api/clients/" + client.getId() + "/sales-reps");

        assertThat(reponse.getBody()).contains("Camille Durand").contains("camille@test.fr");
    }

    @Test
    void clientInconnuRend404() {
        ResponseEntity<String> reponse =
                appelAuthentifie("/api/clients/" + UUID.randomUUID() + "/sales-reps");

        assertThat(reponse.getStatusCode().value()).isEqualTo(404);
    }

    private Client creeUnClientAvecUnCommercial() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client de test");
        client.setHmacSecret(SECRET_EN_CLAIR);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081"));
        clientRepository.saveAndFlush(client);

        SalesRep commercial = new SalesRep();
        commercial.setClient(client);
        commercial.setFullName("Camille Durand");
        commercial.setEmail("camille@test.fr");
        commercial.setSector("industrie");
        commercial.setZone("FR");
        salesRepRepository.saveAndFlush(commercial);
        return client;
    }

    private ResponseEntity<String> appelAuthentifie(String chemin) {
        String jeton = (String) rest.postForEntity(
                        "/api/auth/login",
                        Map.of("username", "operateur", "password", "secret-de-test"),
                        Map.class)
                .getBody()
                .get("token");

        HttpHeaders entetes = new HttpHeaders();
        entetes.setBearerAuth(jeton);
        return rest.exchange(chemin, HttpMethod.GET, new HttpEntity<>(entetes), String.class);
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=ClientDirectoryTest`
Expected: FAIL — `/api/clients` rend 404, l'endpoint n'existe pas.

- [ ] **Step 3: Écrire les DTO**

```java
package com.leadflow.monitoring.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.UUID;

/**
 * Le strict necessaire pour alimenter un filtre. Ecrit a la main plutot que derive de
 * l'entite : {@code Client} porte {@code hmacSecret} et {@code crmConfig} dechiffres a la
 * lecture, et le seul moyen sur de ne jamais les publier est de ne jamais serialiser
 * l'entite. Ce record est la barriere.
 */
public record ClientSummary(
        UUID id,
        String name,
        boolean active,
        String crmProviderId,
        AssignmentStrategyType assignmentStrategy) {
}
```

```java
package com.leadflow.monitoring.dto;

import java.util.UUID;

public record SalesRepSummary(
        UUID id,
        String fullName,
        String email,
        String sector,
        String zone,
        boolean active,
        String crmRef) {
}
```

- [ ] **Step 4: Écrire `ClientDirectoryService`**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.ClientSummary;
import com.leadflow.monitoring.dto.SalesRepSummary;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Annuaire de reference du dashboard. Lecture seule, comme tout {@code monitoring/}.
 *
 * <p>Aucune pagination : le nombre de clients d'une agence tient sur un ecran, et un filtre
 * qui se chargerait en deux temps serait moins utilisable qu'une liste complete.
 */
@Service
public class ClientDirectoryService {

    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;

    public ClientDirectoryService(ClientRepository clients, SalesRepRepository commerciaux) {
        this.clients = clients;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public List<ClientSummary> tousLesClients() {
        return clients.findAll(Sort.by("name")).stream()
                .map(this::resume)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SalesRepSummary> commerciauxDe(UUID clientId) {
        if (!clients.existsById(clientId)) {
            throw new RessourceIntrouvableException("Client inconnu : " + clientId);
        }
        return commerciaux.findByClientId(clientId).stream()
                .map(this::resume)
                .toList();
    }

    private ClientSummary resume(Client client) {
        return new ClientSummary(
                client.getId(),
                client.getName(),
                client.isActive(),
                client.getCrmProviderId(),
                client.getAssignmentStrategy());
    }

    private SalesRepSummary resume(SalesRep commercial) {
        return new SalesRepSummary(
                commercial.getId(),
                commercial.getFullName(),
                commercial.getEmail(),
                commercial.getSector(),
                commercial.getZone(),
                commercial.isActive(),
                commercial.getCrmRef());
    }
}
```

`SalesRepRepository` ne porte aujourd'hui que `findByClientIdAndActiveTrue`, dont F4 se sert
pour l'attribution. L'écran doit voir aussi les inactifs — c'est même l'explication d'un tour
de rôle déséquilibré. Ajouter :

```java
    /** Tous les commerciaux d'un client, actifs ou non : l'ecran doit montrer les deux. */
    List<SalesRep> findByClientId(UUID clientId);
```

- [ ] **Step 5: Écrire le contrôleur**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.ClientSummary;
import com.leadflow.monitoring.dto.SalesRepSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
public class ClientDirectoryController {

    private final ClientDirectoryService service;

    public ClientDirectoryController(ClientDirectoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientSummary> clients() {
        return service.tousLesClients();
    }

    @GetMapping("/{id}/sales-reps")
    public List<SalesRepSummary> commerciaux(@PathVariable UUID id) {
        return service.commerciauxDe(id);
    }
}
```

- [ ] **Step 6: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=ClientDirectoryTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring \
        backend/src/main/java/com/leadflow/tenant/SalesRepRepository.java \
        backend/src/test/java/com/leadflow/monitoring/ClientDirectoryTest.java
git commit -m "feat: annuaire des clients et des commerciaux, sans fuite de secret

Les deux records sont ecrits a la main plutot que derives des entites : Client
porte hmacSecret et crmConfig que les converters dechiffrent a la lecture, et
ne jamais serialiser l'entite est la seule garantie qui tienne. Le test
l'asserte sur le corps JSON, pas sur le DTO."
```

---

## Task 5: Statistiques agrégées

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/StatsView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/StatsService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/StatsController.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/StatsRepository.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/Comptage.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/StatsServiceTest.java`

**Interfaces:**
- Consumes: tables `lead` et `raw_lead_event`, lues par des agrégats SQL.
- Produces: `GET /api/stats?clientId=&from=&to=` -> `StatsView`. Consommé par T15.

Trois règles portées par cette tâche. **Tout en agrégats** : `count` + `group by`, jamais un
chargement de lignes — c'est ce que servent les index `idx_lead_client_status` et
`idx_raw_lead_event_client_received` posés en F1. **Zéros compris** : un statut absent du
résultat doit sortir à `0`, sans quoi l'écran affiche des trous. **Aucune série temporelle** :
sans bibliothèque de graphiques (3.13), elle n'aurait pas de consommateur.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.capture.RawLeadEvent;
import com.leadflow.capture.RawLeadEventRepository;
import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.monitoring.dto.StatsView;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatsServiceTest {

    @Autowired private StatsService service;
    @Autowired private LeadRepository leadRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;

    @AfterEach
    void nettoie() {
        leadRepository.deleteAll();
        rawLeadEventRepository.deleteAll();
        clientRepository.deleteAll();
    }

    @Test
    void compteLesLeadsParStatutZerosCompris() {
        UUID clientId = creeUnClient();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis");
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.RULES, "devis");
        creeUnLead(clientId, LeadStatus.QUALIFIED, IntentSource.GEMINI, "information");

        StatsView stats = service.calcule(clientId, null, null);

        assertThat(stats.leadsParStatut())
                .containsEntry("SYNCED", 2L)
                .containsEntry("QUALIFIED", 1L)
                // Les cinq valeurs sont presentes : un trou dans la carte ferait un trou
                // dans l'ecran.
                .containsEntry("REJECTED", 0L)
                .containsEntry("ROUTED", 0L)
                .containsEntry("FAILED", 0L);
    }

    @Test
    void calculeLeTauxDeConversion() {
        UUID clientId = creeUnClient();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis");
        creeUnLead(clientId, LeadStatus.QUALIFIED, IntentSource.GEMINI, "devis");

        assertThat(service.calcule(clientId, null, null).tauxDeConversion()).isEqualTo(0.5d);
    }

    @Test
    void mesureLaPartDuModeDegrade() {
        UUID clientId = creeUnClient();
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.GEMINI, "devis");
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.RULES, "devis");
        creeUnLead(clientId, LeadStatus.SYNCED, IntentSource.RULES, "devis");

        StatsView stats = service.calcule(clientId, null, null);

        // Raison d'etre de la colonne intent_source, ajoutee en F3 et jamais lue depuis :
        // si Gemini tombe en panne de quota, le pipeline continue en silence, et c'est ici
        // que ca se voit.
        assertThat(stats.leadsParSourceDIntention())
                .containsEntry("GEMINI", 1L)
                .containsEntry("RULES", 2L);
    }

    @Test
    void compteLesEvenementsBrutsParStatutDiscardedCompris() {
        UUID clientId = creeUnClient();
        creeUnEvenement(clientId, RawLeadEventStatus.PUBLISHED);
        creeUnEvenement(clientId, RawLeadEventStatus.DISCARDED);

        StatsView stats = service.calcule(clientId, null, null);

        assertThat(stats.evenementsParStatut())
                .containsEntry("PUBLISHED", 1L)
                .containsEntry("DISCARDED", 1L)
                .containsEntry("RECEIVED", 0L)
                .containsEntry("FAILED", 0L);
    }

    @Test
    void leFiltreClientIsoleLesAgregats() {
        UUID premier = creeUnClient();
        UUID second = creeUnClient();
        creeUnLead(premier, LeadStatus.SYNCED, IntentSource.GEMINI, "devis");
        creeUnLead(second, LeadStatus.SYNCED, IntentSource.GEMINI, "devis");

        assertThat(service.calcule(premier, null, null).total()).isEqualTo(1L);
        assertThat(service.calcule(null, null, null).total()).isEqualTo(2L);
    }

    private UUID creeUnClient() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client " + UUID.randomUUID());
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        return clientRepository.saveAndFlush(client).getId();
    }

    private void creeUnLead(UUID clientId, LeadStatus statut, IntentSource source, String intent) {
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(UUID.randomUUID());
        lead.setEmail(UUID.randomUUID() + "@test.fr");
        lead.setStatus(statut);
        lead.setIntentSource(source);
        lead.setDetectedIntent(intent);
        lead.setScore(50);
        leadRepository.saveAndFlush(lead);
    }

    private void creeUnEvenement(UUID clientId, RawLeadEventStatus statut) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire");
        evenement.setPayload(new HashMap<>(Map.of("email", "a@b.fr")));
        evenement.setSignature("t=1,v1=" + UUID.randomUUID());
        evenement.setStatus(statut);
        rawLeadEventRepository.saveAndFlush(evenement);
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=StatsServiceTest`
Expected: FAIL — `StatsService` n'existe pas, la compilation du test échoue.

- [ ] **Step 3: Écrire la projection et le repository d'agrégats**

`monitoring/Comptage.java` :

```java
package com.leadflow.monitoring;

/**
 * Projection d'interface d'un {@code group by}. Spring Data la remplit sans passer par
 * l'entite : c'est ce qui garantit qu'aucune ligne n'est chargee pour compter des lignes.
 */
public interface Comptage {

    String getCle();

    long getTotal();
}
```

`monitoring/StatsRepository.java` :

```java
package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Agregats du dashboard. {@code Repository} nu, comme {@link LeadQueryRepository} : le
 * monitoring n'ecrit pas, et l'interface le montre.
 *
 * <p>Les filtres facultatifs sont traites par le motif {@code :param is null or ...}
 * plutot que par des Specifications : une agregation n'a pas de forme dynamique, et cinq
 * requetes nommees se relisent mieux qu'un constructeur de criteres.
 */
public interface StatsRepository extends Repository<Lead, UUID> {

    @Query("""
            select cast(l.status as string) as cle, count(l) as total from Lead l
            where (:clientId is null or l.clientId = :clientId)
              and (:from is null or l.createdAt >= :from)
              and (:to is null or l.createdAt < :to)
            group by l.status
            """)
    List<Comptage> leadsParStatut(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select coalesce(l.detectedIntent, 'inconnue') as cle, count(l) as total from Lead l
            where (:clientId is null or l.clientId = :clientId)
              and (:from is null or l.createdAt >= :from)
              and (:to is null or l.createdAt < :to)
            group by l.detectedIntent
            """)
    List<Comptage> leadsParIntention(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select cast(l.intentSource as string) as cle, count(l) as total from Lead l
            where l.intentSource is not null
              and (:clientId is null or l.clientId = :clientId)
              and (:from is null or l.createdAt >= :from)
              and (:to is null or l.createdAt < :to)
            group by l.intentSource
            """)
    List<Comptage> leadsParSourceDIntention(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select cast(l.assignedSalesRepId as string) as cle, count(l) as total from Lead l
            where l.assignedSalesRepId is not null
              and (:clientId is null or l.clientId = :clientId)
              and (:from is null or l.createdAt >= :from)
              and (:to is null or l.createdAt < :to)
            group by l.assignedSalesRepId
            """)
    List<Comptage> leadsParCommercial(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select cast(e.status as string) as cle, count(e) as total
            from com.leadflow.capture.RawLeadEvent e
            where (:clientId is null or e.clientId = :clientId)
              and (:from is null or e.receivedAt >= :from)
              and (:to is null or e.receivedAt < :to)
            group by e.status
            """)
    List<Comptage> evenementsParStatut(
            @Param("clientId") UUID clientId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
```

- [ ] **Step 4: Écrire `StatsView`**

```java
package com.leadflow.monitoring.dto;

import java.util.Map;

/**
 * Tout l'ecran de statistiques en un appel. Un endpoint par compteur multiplierait les
 * allers-retours pour un ecran qui se lit d'un bloc.
 *
 * <p>Aucune serie temporelle : sans bibliotheque de graphiques (decision 3.13 de la spec),
 * elle n'aurait aucun consommateur. La requete par jour s'ajoutera avec le graphique.
 */
public record StatsView(
        long total,
        Map<String, Long> leadsParStatut,
        Map<String, Long> evenementsParStatut,
        double tauxDeConversion,
        Map<String, Long> leadsParIntention,
        Map<String, Long> leadsParSourceDIntention,
        Map<String, Long> leadsParCommercial,
        Map<String, String> nomsDeCommercial) {
}
```

- [ ] **Step 5: Écrire `StatsService`**

```java
package com.leadflow.monitoring;

import com.leadflow.capture.RawLeadEventStatus;
import com.leadflow.monitoring.dto.StatsView;
import com.leadflow.qualification.IntentSource;
import com.leadflow.qualification.LeadStatus;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agregats du dashboard, calcules en base et jamais en memoire : compter en chargeant les
 * lignes ferait payer a l'ecran le volume de la table.
 *
 * <p>Les cartes sont completees a zero pour toutes les valeurs de l'enumeration. Une valeur
 * absente du {@code group by} veut dire « aucune ligne », pas « pas de donnee » : la
 * distinction se perd cote client si le serveur ne la tranche pas.
 */
@Service
public class StatsService {

    private final StatsRepository stats;
    private final SalesRepRepository commerciaux;

    public StatsService(StatsRepository stats, SalesRepRepository commerciaux) {
        this.stats = stats;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public StatsView calcule(UUID clientId, Instant from, Instant to) {
        Map<String, Long> parStatut = complete(
                carte(stats.leadsParStatut(clientId, from, to)),
                Arrays.stream(LeadStatus.values()).map(Enum::name).toList());

        Map<String, Long> parStatutDEvenement = complete(
                carte(stats.evenementsParStatut(clientId, from, to)),
                Arrays.stream(RawLeadEventStatus.values()).map(Enum::name).toList());

        Map<String, Long> parSource = complete(
                carte(stats.leadsParSourceDIntention(clientId, from, to)),
                Arrays.stream(IntentSource.values()).map(Enum::name).toList());

        Map<String, Long> parCommercial = carte(stats.leadsParCommercial(clientId, from, to));

        long total = parStatut.values().stream().mapToLong(Long::longValue).sum();
        long synchronises = parStatut.getOrDefault(LeadStatus.SYNCED.name(), 0L);

        return new StatsView(
                total,
                parStatut,
                parStatutDEvenement,
                total == 0 ? 0d : (double) synchronises / total,
                carte(stats.leadsParIntention(clientId, from, to)),
                parSource,
                parCommercial,
                nomsDeCommercial(parCommercial.keySet()));
    }

    private Map<String, Long> carte(List<Comptage> comptages) {
        Map<String, Long> resultat = new LinkedHashMap<>();
        comptages.forEach(comptage -> resultat.put(comptage.getCle(), comptage.getTotal()));
        return resultat;
    }

    private Map<String, Long> complete(Map<String, Long> carte, List<String> clesAttendues) {
        Map<String, Long> resultat = new LinkedHashMap<>();
        clesAttendues.forEach(cle -> resultat.put(cle, carte.getOrDefault(cle, 0L)));
        return resultat;
    }

    /**
     * Les identifiants seuls ne disent rien de l'equite du tour de role. Une seule requete,
     * bornee par le nombre de commerciaux ayant recu au moins un lead.
     */
    private Map<String, String> nomsDeCommercial(Set<String> identifiants) {
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        List<UUID> uuids = identifiants.stream().map(UUID::fromString).toList();
        return commerciaux.findAllById(uuids).stream()
                .collect(Collectors.toMap(
                        commercial -> commercial.getId().toString(), SalesRep::getFullName));
    }
}
```

- [ ] **Step 6: Écrire le contrôleur**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.StatsView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping
    public StatsView stats(
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.calcule(clientId, from, to);
    }
}
```

- [ ] **Step 7: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=StatsServiceTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring \
        backend/src/test/java/com/leadflow/monitoring/StatsServiceTest.java
git commit -m "feat: agregats du dashboard en une requete par mesure

Projections d'interface sur des group by : aucune ligne n'est chargee pour en
compter. Les cartes sont completees a zero pour toutes les valeurs de chaque
enumeration, faute de quoi l'ecran confondrait « aucune ligne » et « pas de
donnee ». La part GEMINI contre RULES rend enfin lisible le mode degrade que
F3 avait rendu mesurable sans jamais le lire."
```

---

## Task 6: Santé des connecteurs

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/ConnectorView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/ConnectorClientActivity.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/ConnectorHealthService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/ConnectorHealthController.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/SyncActivityRepository.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/ConnectorHealthServiceTest.java`

**Interfaces:**
- Consumes: `List<CrmConnector>` et `CrmProperties` (F5), table `crm_sync_attempt`.
- Produces: `GET /api/connectors` -> `List<ConnectorView>`. Consommé par T16.

**Aucun appel vers l'ERP** — la règle centrale de cette tâche. Il faudrait déchiffrer le
`crm_config` de chaque client pour construire l'appel, un ERP lent bloquerait l'écran, et le
port `CrmConnector` n'a pas d'opération de santé : lui en ajouter une obligerait **chaque
futur adaptateur** à l'implémenter pour le confort d'un écran. L'état est dérivé des traces,
et « dernier succès il y a 3 minutes » est plus honnête qu'un voyant vert sur une instance
qui refuse les écritures.

L'écran doit distinguer trois situations, ce qu'exige le critère de recette 6 : fournisseur
**désactivé** par configuration, fournisseur **actif sans aucune trace**, fournisseur actif
dont la **dernière synchronisation a échoué**.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptRepository;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.ConnectorView;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConnectorHealthServiceTest {

    @Autowired private ConnectorHealthService service;
    @Autowired private CrmSyncAttemptRepository attempts;
    @Autowired private LeadRepository leads;
    @Autowired private ClientRepository clients;

    @AfterEach
    void nettoie() {
        attempts.deleteAll();
        leads.deleteAll();
        clients.deleteAll();
    }

    @Test
    void listeLesFournisseursDeclaresMemeSansAucuneTrace() {
        List<ConnectorView> vues = service.etatDesConnecteurs();

        assertThat(vues).extracting(ConnectorView::providerId).contains("dolibarr", "odoo");
        assertThat(vues).allSatisfy(vue -> assertThat(vue.enabled()).isTrue());
        assertThat(vues).allSatisfy(vue -> assertThat(vue.successCount()).isZero());
    }

    @Test
    void agregeSuccesEtEchecsParFournisseur() {
        UUID clientId = creeUnClient();
        UUID leadId = creeUnLead(clientId);
        trace(leadId, "dolibarr", CrmSyncAttemptStatus.SUCCESS, null);
        trace(leadId, "dolibarr", CrmSyncAttemptStatus.FAILED, "401 Unauthorized");

        ConnectorView dolibarr = trouve("dolibarr");

        assertThat(dolibarr.successCount()).isEqualTo(1L);
        assertThat(dolibarr.failureCount()).isEqualTo(1L);
        assertThat(dolibarr.lastFailureMessage()).isEqualTo("401 Unauthorized");
        assertThat(dolibarr.lastSuccessAt()).isNotNull();
    }

    @Test
    void detailleLActiviteParClient() {
        UUID premier = creeUnClient();
        UUID second = creeUnClient();
        trace(creeUnLead(premier), "dolibarr", CrmSyncAttemptStatus.SUCCESS, null);
        trace(creeUnLead(second), "dolibarr", CrmSyncAttemptStatus.FAILED, "timeout");

        assertThat(trouve("dolibarr").parClient()).hasSize(2);
    }

    private ConnectorView trouve(String providerId) {
        return service.etatDesConnecteurs().stream()
                .filter(vue -> vue.providerId().equals(providerId))
                .findFirst()
                .orElseThrow();
    }

    private UUID creeUnClient() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Client " + UUID.randomUUID());
        client.setHmacSecret("secret");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of());
        return clients.saveAndFlush(client).getId();
    }

    private UUID creeUnLead(UUID clientId) {
        Lead lead = new Lead();
        lead.setClientId(clientId);
        lead.setRawEventId(UUID.randomUUID());
        lead.setEmail(UUID.randomUUID() + "@test.fr");
        lead.setScore(10);
        return leads.saveAndFlush(lead).getId();
    }

    private void trace(UUID leadId, String providerId, CrmSyncAttemptStatus statut, String erreur) {
        CrmSyncAttempt tentative = new CrmSyncAttempt();
        tentative.setLeadId(leadId);
        tentative.setProviderId(providerId);
        tentative.setStatus(statut);
        tentative.setErrorMessage(erreur);
        attempts.saveAndFlush(tentative);
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=ConnectorHealthServiceTest`
Expected: FAIL — `ConnectorHealthService` n'existe pas.

- [ ] **Step 3: Écrire les DTO**

```java
package com.leadflow.monitoring.dto;

import java.time.Instant;
import java.util.List;

/**
 * Etat d'un fournisseur ERP, <b>derive des traces</b> et jamais d'un appel vers l'ERP : il
 * faudrait dechiffrer le crm_config de chaque client pour construire l'appel, un ERP lent
 * bloquerait le chargement de l'ecran, et le port CrmConnector n'a pas d'operation de
 * sante — lui en ajouter une obligerait chaque futur adaptateur a l'implementer.
 *
 * <p>{@code enabled} vient de la configuration, le reste des traces : c'est ce qui permet a
 * l'ecran de distinguer un fournisseur desactive d'un fournisseur actif dont la derniere
 * synchronisation a echoue.
 */
public record ConnectorView(
        String providerId,
        boolean implemented,
        boolean enabled,
        long successCount,
        long failureCount,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastFailureMessage,
        List<ConnectorClientActivity> parClient) {
}
```

```java
package com.leadflow.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

public record ConnectorClientActivity(
        UUID clientId,
        String clientName,
        long successCount,
        long failureCount,
        Instant lastAttemptAt) {
}
```

- [ ] **Step 4: Écrire le repository d'activité**

`crm_sync_attempt` ne porte pas de `client_id` : la trace est rattachée au lead. La
ventilation par client passe donc par une jointure sur `Lead`, écrite ici plutôt que dans
`crm/` — le monitoring déclare ses propres requêtes (règle 3.4).

```java
package com.leadflow.monitoring;

import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Activite reelle des connecteurs, lue dans la trace append-only de F5.
 *
 * <p>La jointure vers {@code Lead} se fait sur l'identifiant et non sur une association :
 * {@code CrmSyncAttempt} n'en porte aucune, choix de F5 qui garde l'adaptateur ignorant du
 * pipeline. Le prix est une jointure explicite dans la requete, bornee par le nombre de
 * fournisseurs et de clients — pas par le nombre de traces.
 */
public interface SyncActivityRepository extends Repository<CrmSyncAttempt, UUID> {

    @Query("""
            select a.providerId as providerId,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.SUCCESS
                            then 1L else 0L end) as succes,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.FAILED
                            then 1L else 0L end) as echecs,
                   max(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.SUCCESS
                            then a.attemptedAt else null end) as dernierSucces,
                   max(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.FAILED
                            then a.attemptedAt else null end) as dernierEchec
            from CrmSyncAttempt a
            group by a.providerId
            """)
    List<ActiviteParFournisseur> activiteParFournisseur();

    @Query("""
            select a.providerId as providerId, l.clientId as clientId,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.SUCCESS
                            then 1L else 0L end) as succes,
                   sum(case when a.status = com.leadflow.crm.CrmSyncAttemptStatus.FAILED
                            then 1L else 0L end) as echecs,
                   max(a.attemptedAt) as derniereTentative
            from CrmSyncAttempt a, com.leadflow.qualification.Lead l
            where l.id = a.leadId
            group by a.providerId, l.clientId
            """)
    List<ActiviteParClient> activiteParClient();

    /** Message du dernier echec d'un fournisseur : une ligne, la plus recente. */
    List<CrmSyncAttempt> findTop1ByProviderIdAndStatusOrderByAttemptedAtDesc(
            String providerId, CrmSyncAttemptStatus status);

    interface ActiviteParFournisseur {
        String getProviderId();

        long getSucces();

        long getEchecs();

        Instant getDernierSucces();

        Instant getDernierEchec();
    }

    interface ActiviteParClient {
        String getProviderId();

        UUID getClientId();

        long getSucces();

        long getEchecs();

        Instant getDerniereTentative();
    }
}
```

- [ ] **Step 5: Écrire `ConnectorHealthService`**

```java
package com.leadflow.monitoring;

import com.leadflow.config.CrmProperties;
import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.ConnectorClientActivity;
import com.leadflow.monitoring.dto.ConnectorView;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fusionne deux sources : les fournisseurs <b>declares</b> — connecteurs presents dans le
 * classpath, plus le drapeau {@code enabled} de la configuration — et leur <b>activite
 * reelle</b>, agregee depuis la trace.
 *
 * <p>Un fournisseur declare mais sans trace apparait avec des compteurs a zero : l'absence
 * d'activite est une information, et la taire donnerait un ecran ou un ERP jamais appele
 * ressemble a un ERP absent.
 *
 * <p>La liste des connecteurs est injectee en {@code List<CrmConnector>} plutot que lue
 * dans {@code CrmConnectorRegistry.availableProviders()} : ce dernier filtre deja les
 * desactives, or c'est precisement la distinction que l'ecran doit montrer.
 */
@Service
public class ConnectorHealthService {

    private final List<CrmConnector> connecteurs;
    private final CrmProperties properties;
    private final SyncActivityRepository activite;
    private final ClientRepository clients;

    public ConnectorHealthService(
            List<CrmConnector> connecteurs,
            CrmProperties properties,
            SyncActivityRepository activite,
            ClientRepository clients) {
        this.connecteurs = connecteurs;
        this.properties = properties;
        this.activite = activite;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<ConnectorView> etatDesConnecteurs() {
        Set<String> implementes = connecteurs.stream()
                .map(CrmConnector::providerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, CrmProperties.Provider> declares =
                properties.providers() == null ? Map.of() : properties.providers();

        // Union des deux sources : un connecteur present sans configuration, comme une
        // configuration sans connecteur, sont deux anomalies que l'ecran doit montrer.
        Set<String> tous = new LinkedHashSet<>(implementes);
        tous.addAll(declares.keySet());

        Map<String, SyncActivityRepository.ActiviteParFournisseur> parFournisseur =
                activite.activiteParFournisseur().stream()
                        .collect(Collectors.toMap(
                                SyncActivityRepository.ActiviteParFournisseur::getProviderId,
                                ligne -> ligne));
        Map<UUID, String> nomsDeClient = clients.findAll().stream()
                .collect(Collectors.toMap(Client::getId, Client::getName));
        Map<String, List<ConnectorClientActivity>> detail = detailParFournisseur(nomsDeClient);

        List<ConnectorView> vues = new ArrayList<>();
        for (String providerId : tous) {
            SyncActivityRepository.ActiviteParFournisseur ligne = parFournisseur.get(providerId);
            CrmProperties.Provider reglages = declares.get(providerId);

            vues.add(new ConnectorView(
                    providerId,
                    implementes.contains(providerId),
                    reglages != null && reglages.enabled(),
                    ligne == null ? 0L : ligne.getSucces(),
                    ligne == null ? 0L : ligne.getEchecs(),
                    ligne == null ? null : ligne.getDernierSucces(),
                    ligne == null ? null : ligne.getDernierEchec(),
                    dernierMessageDEchec(providerId),
                    detail.getOrDefault(providerId, List.of())));
        }
        vues.sort(Comparator.comparing(ConnectorView::providerId));
        return vues;
    }

    private Map<String, List<ConnectorClientActivity>> detailParFournisseur(
            Map<UUID, String> nomsDeClient) {
        return activite.activiteParClient().stream()
                .collect(Collectors.groupingBy(
                        SyncActivityRepository.ActiviteParClient::getProviderId,
                        Collectors.mapping(
                                ligne -> new ConnectorClientActivity(
                                        ligne.getClientId(),
                                        nomsDeClient.get(ligne.getClientId()),
                                        ligne.getSucces(),
                                        ligne.getEchecs(),
                                        ligne.getDerniereTentative()),
                                Collectors.toList())));
    }

    private String dernierMessageDEchec(String providerId) {
        return activite
                .findTop1ByProviderIdAndStatusOrderByAttemptedAtDesc(
                        providerId, CrmSyncAttemptStatus.FAILED)
                .stream()
                .findFirst()
                .map(CrmSyncAttempt::getErrorMessage)
                .orElse(null);
    }
}
```

- [ ] **Step 6: Écrire le contrôleur**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.ConnectorView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorHealthController {

    private final ConnectorHealthService service;

    public ConnectorHealthController(ConnectorHealthService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectorView> connecteurs() {
        return service.etatDesConnecteurs();
    }
}
```

- [ ] **Step 7: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=ConnectorHealthServiceTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring \
        backend/src/test/java/com/leadflow/monitoring/ConnectorHealthServiceTest.java
git commit -m "feat: etat des connecteurs derive des traces, sans appel aux ERP

Un ping de sante obligerait chaque futur adaptateur a implementer une operation
de plus sur le port, pour un ecran, et un ERP lent bloquerait le chargement.
« Dernier succes il y a trois minutes » est plus honnete qu'un voyant vert sur
une instance qui refuse les ecritures."
```

---

## Task 7: Migration `V4__dead_letter.sql` et entité du journal

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__dead_letter.sql`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetter.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterStatus.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterRepository.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/package-info.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterPersistenceTest.java`

**Interfaces:**
- Consumes: rien.
- Produces: l'entité `DeadLetter`, son `DeadLetterStatus` et son repository. T8, T9, T10 et
  T12 s'appuient dessus.

**C'est la seule migration de F6.** Aucune autre tâche ne touche au schéma ; une tâche qui
semble en réclamer une a dévié du plan. Elle rompt la promesse « le schéma est complet »
tenue depuis `V3`, ce que la spec assume en 12.6 : une file de messages ne sait pas être une
liste paginée et filtrable, et le critère de recette en exige une.

- [ ] **Step 1: Écrire la migration**

`backend/src/main/resources/db/migration/V4__dead_letter.sql` :

```sql
-- Journal des messages morts. Remplace la DLQ RabbitMQ comme source de verite des leads
-- en echec : une file ne sait ni paginer, ni filtrer, ni trier, ni conserver un motif.
-- La DLQ reste le tuyau par lequel les morts arrivent, et sa profondeur doit rester nulle.
CREATE TABLE dead_letter (
    id              UUID        PRIMARY KEY,
    origin_queue    VARCHAR(80) NOT NULL,
    routing_key     VARCHAR(80) NOT NULL,
    -- Les octets d'origine, jamais deserialises puis re-serialises : un message illisible
    -- doit etre journalise quand meme, et un rejeu doit renvoyer exactement ce qui est
    -- parti.
    payload         TEXT        NOT NULL,
    content_type    VARCHAR(80),
    -- En-tete __TypeId__ du message. Sans lui, le convertisseur ne saurait pas dans quelle
    -- classe deserialiser au rejeu.
    type_id         VARCHAR(255),
    client_id       UUID REFERENCES client (id),
    -- Volontairement sans cle etrangere : le message peut etre corrompu, et une contrainte
    -- ferait echouer l'ecriture du journal exactement quand on en a le plus besoin.
    lead_id         UUID,
    failure_reason  TEXT,
    dead_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          VARCHAR(32) NOT NULL
        CONSTRAINT ck_dead_letter_status CHECK (status IN ('PENDING','REPLAYED','DISCARDED')),
    replayed_at     TIMESTAMPTZ,
    replayed_by     VARCHAR(120)
);

-- Tri par defaut de l'ecran : les morts en attente, du plus recent au plus ancien.
CREATE INDEX idx_dead_letter_status_dead_at ON dead_letter (status, dead_at DESC);

-- Sert le garde-fou des filets de republication (T12) : un lead deja mort et en attente
-- d'action humaine n'est pas republie automatiquement.
CREATE INDEX idx_dead_letter_lead ON dead_letter (lead_id) WHERE lead_id IS NOT NULL;
```

**Aucune contrainte d'unicité, délibérément.** Un même message peut produire deux lignes si
le consommateur meurt entre le commit et l'acquittement : c'est un doublon visible dans un
journal, écartable d'un clic, et le prix à payer pour ne rien perdre.

- [ ] **Step 2: Écrire le test qui échoue**

```java
package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code @SpringBootTest} et non {@code @DataJpaTest} : la tranche de ce dernier n'inclut
 * pas les {@code @Component} que sont les converters chiffres, dont Hibernate a besoin des
 * qu'une entite du projet est chargee.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterPersistenceTest {

    @Autowired private DeadLetterRepository repository;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
    }

    @Test
    void ecritEtRelitUneMortSansLeadNiClient() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.qualified");
        mort.setRoutingKey("lead.qualified");
        mort.setPayload("{ceci n'est pas du json");
        mort.setFailureReason("Charge utile illisible");
        mort.setStatus(DeadLetterStatus.PENDING);

        UUID id = repository.saveAndFlush(mort).getId();

        assertThat(repository.findById(id)).hasValueSatisfying(relue -> {
            assertThat(relue.getPayload()).isEqualTo("{ceci n'est pas du json");
            assertThat(relue.getDeadAt()).isNotNull();
            // Ni client_id ni lead_id : une mort corrompue doit s'ecrire quand meme.
            assertThat(relue.getLeadId()).isNull();
        });
    }

    @Test
    void filtreLesMortsEnAttenteDUnLead() {
        UUID leadId = UUID.randomUUID();
        repository.saveAndFlush(mort(leadId, DeadLetterStatus.PENDING));
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.REPLAYED));

        assertThat(repository.existsByLeadIdAndStatus(leadId, DeadLetterStatus.PENDING)).isTrue();
        assertThat(repository.existsByLeadIdAndStatus(UUID.randomUUID(), DeadLetterStatus.PENDING))
                .isFalse();
    }

    @Test
    void compteLesMortsEnAttente() {
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.PENDING));
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.PENDING));
        repository.saveAndFlush(mort(UUID.randomUUID(), DeadLetterStatus.DISCARDED));

        assertThat(repository.countByStatus(DeadLetterStatus.PENDING)).isEqualTo(2L);
    }

    private DeadLetter mort(UUID leadId, DeadLetterStatus statut) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue("leadflow.leads.qualified");
        mort.setRoutingKey("lead.qualified");
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(statut);
        mort.setDeadAt(Instant.now());
        return mort;
    }
}
```

- [ ] **Step 3: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=DeadLetterPersistenceTest`
Expected: FAIL — l'entité n'existe pas.

- [ ] **Step 4: Écrire l'énumération, l'entité et le repository**

```java
package com.leadflow.monitoring.deadletter;

/**
 * Cycle de vie d'une ligne du journal. {@code REPLAYED} et {@code DISCARDED} sont tous deux
 * terminaux : un rejeu qui echoue de nouveau produit une <b>nouvelle</b> ligne, l'historique
 * se lit et ne s'ecrase pas.
 */
public enum DeadLetterStatus {

    /** Mort constatee, en attente d'une decision humaine. */
    PENDING,

    /** Republiee sur la file d'origine par un operateur. */
    REPLAYED,

    /** Ecartee sans rejeu : doublon, message de test, echec definitivement compris. */
    DISCARDED
}
```

```java
package com.leadflow.monitoring.deadletter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Un message mort, journalise pour etre lisible et rejouable.
 *
 * <p>N'herite pas de {@code BaseEntity} : {@code created_at} / {@code updated_at} n'ont pas
 * de sens ici. Une mort a une date de deces et, eventuellement, une date de rejeu ; les
 * deux sont metier et portent des noms metier.
 *
 * <p>{@code payload} est le texte exact recu. Le journal ne deserialise jamais : c'est ce
 * qui lui permet d'ecrire une ligne pour un message illisible, et de rejouer des octets
 * identiques a ceux qui sont partis.
 */
@Entity
@Table(name = "dead_letter")
@Getter
@Setter
public class DeadLetter {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "origin_queue", nullable = false, length = 80)
    private String originQueue;

    /** Cle de routage d'origine, celle dont le rejeu a besoin pour republier au bon endroit. */
    @Column(name = "routing_key", nullable = false, length = 80)
    private String routingKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "content_type", length = 80)
    private String contentType;

    /** En-tete {@code __TypeId__}, sans lequel le rejeu ne peut pas etre deserialise. */
    @Column(name = "type_id", length = 255)
    private String typeId;

    @Column(name = "client_id")
    private UUID clientId;

    /** Sans cle etrangere : un message corrompu doit pouvoir etre journalise malgre tout. */
    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "dead_at", nullable = false)
    private Instant deadAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeadLetterStatus status = DeadLetterStatus.PENDING;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    /** Nom de l'operateur tire du jeton. Seule trace de qui a fait quoi dans tout F6. */
    @Column(name = "replayed_by", length = 120)
    private String replayedBy;

    @PrePersist
    void onCreate() {
        if (this.deadAt == null) {
            this.deadAt = Instant.now();
        }
    }
}
```

```java
package com.leadflow.monitoring.deadletter;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Seule table que {@code monitoring/} ecrit, donc seul repository du package qui expose
 * l'ecriture. {@code JpaSpecificationExecutor} sert les filtres de l'ecran, sur le meme
 * schema que {@code LeadQueryRepository}.
 *
 * <p>{@link #existsByLeadIdAndStatus} est le garde-fou des filets de republication (T12) :
 * un echec deja constate et presente a un humain n'est plus republie automatiquement.
 */
public interface DeadLetterRepository
        extends JpaRepository<DeadLetter, UUID>, JpaSpecificationExecutor<DeadLetter> {

    boolean existsByLeadIdAndStatus(UUID leadId, DeadLetterStatus status);

    long countByStatus(DeadLetterStatus status);
}
```

```java
/**
 * Journal des messages morts : ecriture depuis la DLQ, lecture par l'ecran, rejeu unitaire.
 *
 * <p>Ce package contient la <b>seule table que le monitoring ecrit</b> et le <b>seul verbe
 * d'ecriture metier de F6</b> — un rejeu, qui remet dans la file un message qui en venait,
 * a l'identique et jamais fabrique.
 */
package com.leadflow.monitoring.deadletter;
```

- [ ] **Step 5: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=DeadLetterPersistenceTest` puis `./mvnw test`
Expected: PASS. Si Flyway refuse de démarrer sur un checksum, c'est qu'une migration
existante a été modifiée : `docker compose down -v` en dev, et ne jamais toucher `V1` à `V3`.

```bash
git add backend/src/main/resources/db/migration/V4__dead_letter.sql \
        backend/src/main/java/com/leadflow/monitoring/deadletter \
        backend/src/test/java/com/leadflow/monitoring/deadletter
git commit -m "feat: table dead_letter, journal des messages morts

Une file de messages ne sait ni paginer, ni filtrer, ni trier, ni conserver un
motif d'echec : le critere de recette de F6 exige exactement cela. La DLQ reste
le tuyau, la table devient la source de verite. Pas de contrainte d'unicite —
un doublon visible vaut mieux qu'une perte — et pas de cle etrangere sur
lead_id, un message corrompu devant pouvoir etre journalise quand meme."
```

---

## Task 8: Capture de la cause et consommation de la DLQ

**Files:**
- Create: `backend/src/main/java/com/leadflow/config/MonitoringProperties.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterListener.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterJournal.java`
- Modify: `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterListenerTest.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/deadletter/CheminDEchecCompletTest.java`

**Interfaces:**
- Consumes: `leadflow.leads.dlq`, l'entité de T7.
- Produces: des lignes `dead_letter` en `PENDING`. T9 les rejoue, T10 les compte, T12 les
  consulte, T11 les diffuse.

Deux mécanismes indissociables. `RepublishMessageRecoverer` **capture la cause** : sans lui,
l'en-tête `x-death` de RabbitMQ ne contient que le motif générique `rejected`, et un écran
qui liste des morts sans dire pourquoi n'oriente personne. Le listener **ne perd rien** et
distingue deux échecs : une charge utile inexploitable donne une ligne quand même, une base
indisponible remet le message en file.

- [ ] **Step 1: Écrire les propriétés de monitoring**

```java
package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages de l'observateur. Un seul record pour les trois mecanismes de F6 : ils n'ont de
 * sens qu'ensemble, et les separer multiplierait les prefixes sans rien clarifier.
 *
 * @param deadletter consommateur de la DLQ
 * @param stream     flux SSE et son consommateur
 * @param relay      filets de republication du pipeline (T12)
 */
@ConfigurationProperties(prefix = "leadflow.monitoring")
public record MonitoringProperties(
        Deadletter deadletter, Stream stream, Relay relay) {

    public record Deadletter(Listener listener) {
    }

    public record Stream(
            Listener listener, Duration emitterTimeout, Duration heartbeatInterval) {
    }

    /**
     * @param qualifiedAfter age minimal d'un lead QUALIFIED avant republication
     * @param routedAfter    age minimal d'un lead ROUTED avant republication
     * @param interval       periode de balayage
     */
    public record Relay(
            Duration qualifiedAfter, Duration routedAfter, Duration interval) {
    }

    public record Listener(boolean enabled) {
    }
}
```

Et dans `application.yml`, sous `leadflow:` :

```yaml
  monitoring:
    deadletter:
      listener:
        # Presence du consommateur de la DLQ. Toujours vrai en production ; la suite de
        # tests le retire, sauf la ou elle l'eprouve.
        enabled: true
    stream:
      listener:
        enabled: true
      emitter-timeout: 30m
      heartbeat-interval: 20s
    relay:
      qualified-after: 2m
      routed-after: 2m
      interval: 30s
```

Et dans `src/test/resources/application.properties`, la même ligne que pour les quatre
autres consommateurs :

```properties
# Le journal des morts n'est pas actif dans la suite : les tests des etapes amont
# produisent des echecs volontaires, et un journal actif ecrirait des lignes qu'ils
# n'attendent pas. Les tests qui l'eprouvent le rallument par @TestPropertySource.
leadflow.monitoring.deadletter.listener.enabled=false
leadflow.monitoring.stream.listener.enabled=false
```

- [ ] **Step 2: Écrire le test qui échoue**

`DeadLetterListenerTest` — le listener rallumé, on publie directement sur la DLX :

```java
package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "leadflow.monitoring.deadletter.listener.enabled=true")
class DeadLetterListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private DeadLetterRepository repository;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
    }

    @Test
    void journaliseUnMessageMortAvecSaCauseEtSonTypeId() {
        UUID leadId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        String corps = """
                {"leadId":"%s","clientId":"%s","score":42,"qualifiedAt":"2026-08-23T10:00:00Z"}
                """.formatted(leadId, clientId);

        MessageProperties proprietes = new MessageProperties();
        proprietes.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        proprietes.setHeader("__TypeId__", "com.leadflow.qualification.QualifiedLeadMessage");
        proprietes.setHeader("x-original-routingKey", RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        proprietes.setHeader("x-first-death-queue", RabbitMQConfig.QUALIFIED_QUEUE);
        proprietes.setHeader("x-exception-message", "Aucun commercial actif pour ce client");
        Message message = MessageBuilder.withBody(corps.getBytes()).andProperties(proprietes)
                .build();

        rabbitTemplate.send(RabbitMQConfig.DLX_EXCHANGE, RabbitMQConfig.DLQ_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findAll()).singleElement().satisfies(mort -> {
                assertThat(mort.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
                assertThat(mort.getRoutingKey())
                        .isEqualTo(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
                assertThat(mort.getTypeId())
                        .isEqualTo("com.leadflow.qualification.QualifiedLeadMessage");
                assertThat(mort.getFailureReason())
                        .contains("Aucun commercial actif pour ce client");
                assertThat(mort.getLeadId()).isEqualTo(leadId);
                assertThat(mort.getPayload()).contains(leadId.toString());
            });
        });
    }

    @Test
    void journaliseQuandMemeUneChargeUtileIllisible() {
        MessageProperties proprietes = new MessageProperties();
        proprietes.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = MessageBuilder.withBody("{tronque".getBytes())
                .andProperties(proprietes).build();

        rabbitTemplate.send(RabbitMQConfig.DLX_EXCHANGE, RabbitMQConfig.DLQ_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.findAll()).singleElement().satisfies(mort -> {
                // Ce n'est pas une erreur du journal : la ligne est ecrite avec ce qu'on a,
                // et failure_reason dit ce qu'on n'a pas su lire.
                assertThat(mort.getPayload()).isEqualTo("{tronque");
                assertThat(mort.getLeadId()).isNull();
                assertThat(mort.getRoutingKey()).isNotBlank();
                assertThat(mort.getFailureReason()).contains("illisible");
            });
        });
    }
}
```

`CheminDEchecCompletTest` — le test qui prouve que le `RepublishMessageRecoverer` sert à
quelque chose : un listener qui lève, trois tentatives, et une ligne portant **le message de
l'exception**.

```java
package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.routing.RoutedLeadMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Le seul test qui parcourt le chemin d'echec en entier : un consommateur qui leve, les
 * trois tentatives, le recoverer, la DLX, la DLQ, le journal. Sans lui, rien ne garantit
 * que le motif affiche a l'ecran est bien celui de l'exception levee.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, CheminDEchecCompletTest.ConsommateurQuiEchoue.class})
@TestPropertySource(properties = {
        "leadflow.monitoring.deadletter.listener.enabled=true",
        // Trois tentatives rapides : le backoff par defaut ferait durer le test.
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=1"})
class CheminDEchecCompletTest {

    static final String MOTIF = "Aucun commercial actif pour ce client";

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private DeadLetterRepository repository;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
    }

    @Test
    void troisEchecsProduisentUneLignePortantLeMessageDeLException() {
        UUID leadId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.ROUTED_ROUTING_KEY,
                new RoutedLeadMessage(leadId, UUID.randomUUID(), UUID.randomUUID(),
                        Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findAll()).singleElement().satisfies(mort -> {
                    assertThat(mort.getFailureReason()).contains(MOTIF);
                    assertThat(mort.getRoutingKey())
                            .isEqualTo(RabbitMQConfig.ROUTED_ROUTING_KEY);
                    assertThat(mort.getLeadId()).isEqualTo(leadId);
                }));
    }

    @TestConfiguration
    static class ConsommateurQuiEchoue {

        @RabbitListener(queues = RabbitMQConfig.ROUTED_QUEUE)
        void recoit(RoutedLeadMessage message) {
            throw new IllegalStateException(MOTIF);
        }
    }
}
```

- [ ] **Step 3: Vérifier que les tests échouent**

Run: `cd backend && ./mvnw test -Dtest=DeadLetterListenerTest+CheminDEchecCompletTest`
Expected: FAIL — aucun consommateur de DLQ, et aucune cause capturée.

- [ ] **Step 4: Modifier `RabbitMQConfig`**

Trois ajouts. Le `RepublishMessageRecoverer` :

```java
    /**
     * Remplace le rejet par defaut a l'epuisement des trois tentatives : Spring AMQP
     * republie lui-meme le message vers la DLX en ajoutant {@code x-exception-message},
     * {@code x-exception-stacktrace}, {@code x-original-exchange} et
     * {@code x-original-routingKey}.
     *
     * <p>Deux gains, tous deux indispensables a l'ecran : la <b>cause</b> de l'echec, que
     * l'en-tete {@code x-death} pose par le broker ne contient pas — il ne dit que
     * « rejected » — et la <b>cle de routage d'origine explicite</b>, celle dont le rejeu a
     * besoin, au lieu d'etre deduite de {@code x-death}.
     *
     * <p>S'applique identiquement aux trois etapes du pipeline, sans changer une ligne de
     * leur code.
     */
    @Bean
    MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE, DLQ_ROUTING_KEY);
    }
```

La fabrique de conteneurs propre à la DLQ :

```java
    /**
     * Fabrique dediee au consommateur de la DLQ. Elle differe de la fabrique par defaut sur
     * un point : {@code defaultRequeueRejected = true}.
     *
     * <p>La DLQ n'a elle-meme aucune DLX. Acquitter un message qu'on n'a pas su journaliser
     * — Postgres indisponible — le perdrait definitivement ; le remettre en file le fera
     * reprendre quand la base reviendra. Le risque de boucle chaude est assume : si Postgres
     * est a terre, l'application entiere l'est.
     */
    @Bean
    SimpleRabbitListenerContainerFactory deadLetterListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory fabrique = new SimpleRabbitListenerContainerFactory();
        fabrique.setConnectionFactory(connectionFactory);
        fabrique.setDefaultRequeueRejected(true);
        // Un seul consommateur : le journal n'est pas un goulot, et la sequence des morts
        // reste lisible.
        fabrique.setConcurrentConsumers(1);
        fabrique.setMaxConcurrentConsumers(1);
        return fabrique;
    }
```

Si Boot 4.1 configure déjà un `MessageRecoverer` par défaut au lieu du rejet, vérifier par
`./mvnw spring-boot:test-run` qu'un message en échec arrive bien dans la DLQ avec
`x-exception-message` avant d'aller plus loin.

- [ ] **Step 5: Écrire `DeadLetterJournal`**

```java
package com.leadflow.monitoring.deadletter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecriture du journal, en transaction propre.
 *
 * <p>Classe distincte du listener pour la meme raison que {@code LeadWriter} en F3 :
 * l'appelant n'est pas transactionnel, et le listener doit pouvoir distinguer « ecrit » de
 * « pas ecrit » pour decider d'acquitter ou de remettre en file. Un {@code REQUIRES_NEW}
 * rend cette frontiere explicite.
 */
@Component
public class DeadLetterJournal {

    private final DeadLetterRepository repository;

    public DeadLetterJournal(DeadLetterRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeadLetter enregistre(DeadLetter mort) {
        return repository.saveAndFlush(mort);
    }
}
```

- [ ] **Step 6: Écrire `DeadLetterListener`**

```java
package com.leadflow.monitoring.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadflow.config.RabbitMQConfig;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consommateur de la DLQ : chaque mort devient une ligne du journal, puis est acquittee.
 *
 * <p>Il recoit un {@link Message} <b>brut</b> et jamais un objet converti : la DLQ contient
 * precisement ce qui a echoue, y compris de l'illisible, et faire tourner Jackson dessus
 * reproduirait l'echec qu'on essaie de consigner.
 *
 * <p>Les deux facons de rater un message ne se traitent pas pareil. Une <b>charge utile
 * inexploitable</b> n'est pas une erreur du journal : la ligne est ecrite avec ce qu'on a,
 * le motif dit ce qu'on n'a pas su lire, et le message est acquitte. Une <b>base
 * indisponible</b>, elle, fait remonter l'exception : la fabrique dediee remet le message
 * dans la DLQ, qui n'a aucune DLX pour le rattraper si on l'acquittait.
 */
@Component
@ConditionalOnProperty(
        name = "leadflow.monitoring.deadletter.listener.enabled", matchIfMissing = true)
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final DeadLetterJournal journal;
    private final ObjectMapper mapper;

    public DeadLetterListener(DeadLetterJournal journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
    }

    @RabbitListener(
            queues = RabbitMQConfig.DLQ_QUEUE,
            containerFactory = "deadLetterListenerContainerFactory")
    public void recoit(Message message) {
        DeadLetter mort = new DeadLetter();
        String corps = new String(message.getBody(), StandardCharsets.UTF_8);
        mort.setPayload(corps);
        mort.setOriginQueue(entete(message, "x-first-death-queue", "inconnue"));
        mort.setRoutingKey(entete(message, "x-original-routingKey", "inconnue"));
        mort.setContentType(message.getMessageProperties().getContentType());
        mort.setTypeId(entete(message, "__TypeId__", null));
        mort.setStatus(DeadLetterStatus.PENDING);

        StringBuilder motif = new StringBuilder();
        String exception = entete(message, "x-exception-message", null);
        if (exception != null) {
            motif.append(exception);
        }
        motif.append(lisIdentifiants(corps, mort));

        mort.setFailureReason(motif.isEmpty() ? "Cause inconnue" : motif.toString());
        journal.enregistre(mort);
        log.info("Mort journalisee : file {}, cle {}", mort.getOriginQueue(), mort.getRoutingKey());
    }

    /**
     * Tente de tirer {@code leadId} et {@code clientId} du corps. L'echec de lecture est
     * une information a consigner, pas une raison de perdre le message : tous nos contrats
     * de file portent ces deux champs, donc ne pas les trouver dit deja quelque chose.
     */
    private String lisIdentifiants(String corps, DeadLetter mort) {
        try {
            JsonNode noeud = mapper.readTree(corps);
            mort.setLeadId(uuid(noeud, "leadId"));
            mort.setClientId(uuid(noeud, "clientId"));
            return "";
        } catch (Exception illisible) {
            return " [charge utile illisible : " + illisible.getMessage() + "]";
        }
    }

    private UUID uuid(JsonNode noeud, String champ) {
        JsonNode valeur = noeud.get(champ);
        if (valeur == null || valeur.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(valeur.asText());
        } catch (IllegalArgumentException pasUnUuid) {
            return null;
        }
    }

    private String entete(Message message, String nom, String defaut) {
        Object valeur = message.getMessageProperties().getHeaders().get(nom);
        return valeur == null ? defaut : valeur.toString();
    }
}
```

`x-first-death-queue` est posé par le broker au dead-lettering ; il survit à la republication
du recoverer. Si le test montre qu'il est absent, se rabattre sur `x-original-exchange` plus
la routing key, et le noter dans le Javadoc.

- [ ] **Step 7: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=DeadLetterListenerTest+CheminDEchecCompletTest`
puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/config \
        backend/src/main/java/com/leadflow/monitoring/deadletter \
        backend/src/main/resources/application.yml \
        backend/src/test/resources/application.properties \
        backend/src/test/java/com/leadflow/monitoring/deadletter
git commit -m "feat: chaque mort devient une ligne du journal, avec sa cause

RepublishMessageRecoverer plutot que le rejet par defaut : l'en-tete x-death du
broker ne dit que « rejected », et un ecran qui liste des morts sans dire
pourquoi n'oriente personne. Le listener recoit le message brut — la DLQ
contient precisement ce qui a echoue — et distingue deux echecs : une charge
utile illisible s'ecrit quand meme, une base indisponible remet en file, la DLQ
n'ayant elle-meme aucune DLX."
```

---

## Task 9: Rejeu et mise à l'écart

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/DeadLetterView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterReplayService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterQueryService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterController.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/DejaTraiteException.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/deadletter/RejeuIndisponibleException.java`
- Modify: `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/deadletter/DeadLetterReplayServiceTest.java`

**Interfaces:**
- Consumes: `DeadLetterRepository` (T7), `RabbitTemplate`, `RessourceIntrouvableException` (T3).
- Produces: `GET /api/dead-letters`, `POST /api/dead-letters/{id}/replay`,
  `POST /api/dead-letters/{id}/discard`. Consommé par T16.

Le rejeu republie **les octets d'origine**, le `content-type` et le `__TypeId__` d'origine.
Sans ce dernier, le convertisseur ne saurait pas dans quelle classe désérialiser — et il
n'est honoré que si le paquet figure dans `RabbitMQConfig.PAQUETS_DE_CONFIANCE`.

Pas de bouton « tout rejouer » : un rejeu de masse sur un incident non compris multiplie
l'incident. L'API rejoue **une** ligne ; l'écran boucle sur une sélection explicite (T16).

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.QualifiedLeadMessage;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeadLetterReplayServiceTest {

    @Autowired private DeadLetterReplayService service;
    @Autowired private DeadLetterRepository repository;
    @Autowired private RabbitTemplate rabbitTemplate;

    @AfterEach
    void nettoie() {
        repository.deleteAll();
        // La file est partagee par la suite : la vider evite qu'un message rejoue ici soit
        // lu par un autre test.
        while (rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 100) != null) {
            // on vide
        }
    }

    @Test
    void republieLesOctetsDOrigineAvecLeMemeTypeId() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.rejoue(mort.getId(), "operateur");

        Message republie = rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000);
        assertThat(republie).isNotNull();
        assertThat(new String(republie.getBody())).isEqualTo(mort.getPayload());
        assertThat(republie.getMessageProperties().getHeaders())
                .containsEntry("__TypeId__", QualifiedLeadMessage.class.getName());
    }

    @Test
    void marqueLaLigneRejoueeAvecLeNomDeLOperateur() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.rejoue(mort.getId(), "camille");

        assertThat(repository.findById(mort.getId())).hasValueSatisfying(relue -> {
            assertThat(relue.getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
            assertThat(relue.getReplayedBy()).isEqualTo("camille");
            assertThat(relue.getReplayedAt()).isNotNull();
        });
    }

    @Test
    void unSecondRejeuEstRefuse() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());
        service.rejoue(mort.getId(), "camille");

        assertThatThrownBy(() -> service.rejoue(mort.getId(), "camille"))
                .isInstanceOf(DejaTraiteException.class);
    }

    @Test
    void ecarterMarqueSansRienRepublier() {
        DeadLetter mort = repository.saveAndFlush(mortEnAttente());

        service.ecarte(mort.getId(), "camille");

        assertThat(repository.findById(mort.getId()).orElseThrow().getStatus())
                .isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 500)).isNull();
    }

    @Test
    void mortInconnueRendUneRessourceIntrouvable() {
        assertThatThrownBy(() -> service.rejoue(UUID.randomUUID(), "camille"))
                .isInstanceOf(com.leadflow.monitoring.RessourceIntrouvableException.class);
    }

    private DeadLetter mortEnAttente() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue(RabbitMQConfig.QUALIFIED_QUEUE);
        mort.setRoutingKey(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        mort.setPayload("{\"leadId\":\"" + UUID.randomUUID() + "\",\"clientId\":\""
                + UUID.randomUUID() + "\",\"score\":42,\"qualifiedAt\":\"2026-08-23T10:00:00Z\"}");
        mort.setContentType("application/json");
        mort.setTypeId(QualifiedLeadMessage.class.getName());
        mort.setStatus(DeadLetterStatus.PENDING);
        return mort;
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=DeadLetterReplayServiceTest`
Expected: FAIL — `DeadLetterReplayService` n'existe pas.

- [ ] **Step 3: Écrire les exceptions et leur traduction HTTP**

```java
package com.leadflow.monitoring.deadletter;

/** Traduite en 409 : une ligne deja REPLAYED ou DISCARDED ne se retraite pas. */
public class DejaTraiteException extends RuntimeException {

    public DejaTraiteException(String message) {
        super(message);
    }
}
```

```java
package com.leadflow.monitoring.deadletter;

/**
 * Traduite en 503 : le broker n'a pas accepte la republication. La ligne reste
 * {@code PENDING}, l'operateur reessaie — c'est exactement la situation ou il ne faut ni
 * marquer, ni perdre.
 */
public class RejeuIndisponibleException extends RuntimeException {

    public RejeuIndisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Dans `common/ApiExceptionHandler.java` :

```java
    @ExceptionHandler(DejaTraiteException.class)
    ProblemDetail dejaTraite(DejaTraiteException echec) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, echec.getMessage());
    }

    @ExceptionHandler(RejeuIndisponibleException.class)
    ProblemDetail rejeuIndisponible(RejeuIndisponibleException echec) {
        log.warn("Rejeu impossible : broker injoignable", echec);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, echec.getMessage());
    }
```

- [ ] **Step 4: Écrire `DeadLetterReplayService`**

```java
package com.leadflow.monitoring.deadletter;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.RessourceIntrouvableException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rejeu unitaire d'un message mort, et mise a l'ecart.
 *
 * <p>Le message republie porte <b>les octets d'origine</b>, le {@code content-type} et le
 * {@code __TypeId__} d'origine : sans ce dernier, le convertisseur ne saurait pas dans
 * quelle classe deserialiser a l'arrivee. Rien n'est reconstruit — republier un objet
 * re-serialise ferait du rejeu autre chose que ce qui a echoue.
 *
 * <p>Aucun rejeu de masse. Un rejeu de masse sur un incident non compris multiplie
 * l'incident ; l'ecran boucle sur une selection explicite s'il le faut.
 *
 * <p><b>Rejouer un {@code lead.qualified} decale la rotation du tour de role</b>, qui n'est
 * pas idempotent : le lead peut changer de commercial. C'est signale a l'ecran, pas empeche
 * — l'empecher demanderait de rouvrir une decision de F4.
 *
 * <p>L'ordre est delibere : on publie d'abord, on marque ensuite. Marquer avant publierait
 * un {@code REPLAYED} qui n'a rien republie si le broker refuse ; dans l'ordre choisi, un
 * echec de marquage laisse une ligne {@code PENDING} pour un message pourtant reparti, ce
 * qui produit au pire un rejeu en double — que la file, elle, sait absorber.
 */
@Service
public class DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

    private final DeadLetterRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public DeadLetterReplayService(
            DeadLetterRepository repository, RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public void rejoue(UUID id, String operateur) {
        DeadLetter mort = enAttente(id);

        MessageProperties proprietes = new MessageProperties();
        if (mort.getContentType() != null) {
            proprietes.setContentType(mort.getContentType());
        }
        if (mort.getTypeId() != null) {
            proprietes.setHeader("__TypeId__", mort.getTypeId());
        }
        // Trace du rejeu dans le message lui-meme : un consommateur qui journalise verra
        // qu'il traite un rejeu et non un premier passage.
        proprietes.setHeader("x-leadflow-replay-of", mort.getId().toString());

        Message message = MessageBuilder
                .withBody(mort.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(proprietes)
                .build();

        try {
            rabbitTemplate.send(
                    RabbitMQConfig.LEADS_EXCHANGE, mort.getRoutingKey(), message);
        } catch (AmqpException echec) {
            throw new RejeuIndisponibleException(
                    "Broker injoignable, la mort reste en attente", echec);
        }

        mort.setStatus(DeadLetterStatus.REPLAYED);
        mort.setReplayedAt(Instant.now());
        mort.setReplayedBy(operateur);
        repository.saveAndFlush(mort);
        log.info("Mort {} rejouee sur {} par {}", id, mort.getRoutingKey(), operateur);
    }

    @Transactional
    public void ecarte(UUID id, String operateur) {
        DeadLetter mort = enAttente(id);
        mort.setStatus(DeadLetterStatus.DISCARDED);
        mort.setReplayedAt(Instant.now());
        mort.setReplayedBy(operateur);
        repository.saveAndFlush(mort);
    }

    private DeadLetter enAttente(UUID id) {
        DeadLetter mort = repository.findById(id).orElseThrow(
                () -> new RessourceIntrouvableException("Mort inconnue : " + id));
        if (mort.getStatus() != DeadLetterStatus.PENDING) {
            throw new DejaTraiteException(
                    "Cette mort est deja " + mort.getStatus() + " : rien a refaire");
        }
        return mort;
    }
}
```

- [ ] **Step 5: Écrire la vue et le service de lecture**

```java
package com.leadflow.monitoring.dto;

import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Ligne du journal telle que l'ecran la lit. {@code replayWarning} est calcule cote serveur
 * plutot que devine cote client : c'est une consequence d'une decision de F4 — le tour de
 * role n'est pas idempotent — et elle appartient au backend.
 */
public record DeadLetterView(
        UUID id,
        String originQueue,
        String routingKey,
        UUID clientId,
        String clientName,
        UUID leadId,
        String failureReason,
        Instant deadAt,
        DeadLetterStatus status,
        Instant replayedAt,
        String replayedBy,
        String payload,
        String replayWarning) {
}
```

```java
package com.leadflow.monitoring.deadletter;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.dto.DeadLetterView;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecture filtree du journal, sur le meme schema que {@code LeadQueryService}. */
@Service
public class DeadLetterQueryService {

    static final String AVERTISSEMENT_REATTRIBUTION =
            "Rejouer ce message refait passer le lead par l'attribution : il peut changer "
                    + "de commercial et la rotation se decale.";

    private final DeadLetterRepository repository;
    private final ClientRepository clients;

    public DeadLetterQueryService(DeadLetterRepository repository, ClientRepository clients) {
        this.repository = repository;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterView> cherche(
            DeadLetterStatus status,
            String originQueue,
            UUID clientId,
            Instant from,
            Instant to,
            Pageable pagination) {

        Page<DeadLetter> page = repository.findAll(
                specification(status, originQueue, clientId, from, to), pagination);

        Set<UUID> identifiants = page.getContent().stream()
                .map(DeadLetter::getClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> noms = identifiants.isEmpty()
                ? Map.of()
                : clients.findAllById(identifiants).stream()
                        .collect(Collectors.toMap(Client::getId, Client::getName));

        List<DeadLetterView> lignes = page.getContent().stream()
                .map(mort -> new DeadLetterView(
                        mort.getId(),
                        mort.getOriginQueue(),
                        mort.getRoutingKey(),
                        mort.getClientId(),
                        noms.get(mort.getClientId()),
                        mort.getLeadId(),
                        mort.getFailureReason(),
                        mort.getDeadAt(),
                        mort.getStatus(),
                        mort.getReplayedAt(),
                        mort.getReplayedBy(),
                        mort.getPayload(),
                        avertissement(mort)))
                .toList();

        return PageResponse.de(page, lignes);
    }

    /**
     * Les trois rejeux n'ont pas le meme risque. {@code lead.captured} est couvert par
     * l'index unique {@code lead.raw_event_id}, {@code lead.routed} par la reconstruction
     * de {@code CrmSyncState} ; {@code lead.qualified} n'a aucun garde-fou.
     */
    private String avertissement(DeadLetter mort) {
        return RabbitMQConfig.QUALIFIED_ROUTING_KEY.equals(mort.getRoutingKey())
                ? AVERTISSEMENT_REATTRIBUTION
                : null;
    }

    private Specification<DeadLetter> specification(
            DeadLetterStatus status, String originQueue, UUID clientId, Instant from, Instant to) {
        return (racine, requete, constructeur) -> {
            List<Predicate> predicats = new ArrayList<>();
            if (status != null) {
                predicats.add(constructeur.equal(racine.get("status"), status));
            }
            if (originQueue != null) {
                predicats.add(constructeur.equal(racine.get("originQueue"), originQueue));
            }
            if (clientId != null) {
                predicats.add(constructeur.equal(racine.get("clientId"), clientId));
            }
            if (from != null) {
                predicats.add(constructeur.greaterThanOrEqualTo(racine.get("deadAt"), from));
            }
            if (to != null) {
                predicats.add(constructeur.lessThan(racine.get("deadAt"), to));
            }
            return constructeur.and(predicats.toArray(Predicate[]::new));
        };
    }
}
```

- [ ] **Step 6: Écrire le contrôleur**

```java
package com.leadflow.monitoring.deadletter;

import com.leadflow.monitoring.dto.DeadLetterView;
import com.leadflow.monitoring.dto.PageResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le nom de l'operateur vient du {@link Principal}, donc du jeton, et jamais d'un parametre
 * de requete : une trace de responsabilite que l'appelant pourrait choisir ne tracerait
 * rien.
 */
@RestController
@RequestMapping("/api/dead-letters")
public class DeadLetterController {

    private final DeadLetterQueryService lecture;
    private final DeadLetterReplayService rejeu;

    public DeadLetterController(DeadLetterQueryService lecture, DeadLetterReplayService rejeu) {
        this.lecture = lecture;
        this.rejeu = rejeu;
    }

    @GetMapping
    public PageResponse<DeadLetterView> liste(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String originQueue,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pageable pagination) {
        return lecture.cherche(status, originQueue, clientId, from, to, pagination);
    }

    @PostMapping("/{id}/replay")
    public void rejoue(@PathVariable UUID id, Principal operateur) {
        rejeu.rejoue(id, operateur.getName());
    }

    @PostMapping("/{id}/discard")
    public void ecarte(@PathVariable UUID id, Principal operateur) {
        rejeu.ecarte(id, operateur.getName());
    }
}
```

- [ ] **Step 7: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=DeadLetterReplayServiceTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring \
        backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java \
        backend/src/test/java/com/leadflow/monitoring/deadletter
git commit -m "feat: rejeu unitaire et trace d'un message mort

Les octets d'origine et le __TypeId__ d'origine sont republies tels quels :
reconstruire l'objet ferait du rejeu autre chose que ce qui a echoue, et sans
le TypeId le convertisseur ne saurait pas quoi instancier. On publie avant de
marquer — un echec de marquage produit au pire un doublon, un marquage avant
publication produirait un REPLAYED qui n'a rien republie. Le nom de l'operateur
vient du jeton : une trace que l'appelant choisirait ne tracerait rien."
```

---

## Task 10: État des files

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/QueueView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/dto/QueuesView.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/QueueService.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/QueueController.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/QueueServiceTest.java`

**Interfaces:**
- Consumes: `RabbitAdmin` (auto-configuré par Spring AMQP), `DeadLetterRepository` (T7).
- Produces: `GET /api/queues` -> `QueuesView`. Consommé par T16.

`RabbitAdmin.getQueueInfo(nom)` interroge le broker par un `queue.declare` **passif**, en
AMQP. Pas de second jeu d'identifiants, pas de dépendance au plugin de management, et rien à
résoudre du port 15672 dans les tests — que `@ServiceConnection` ne câble pas.

Le **nombre de consommateurs** est la mesure la plus lisible d'un listener tombé : un zéro
sur `leadflow.leads.qualified` explique en une seconde pourquoi plus rien n'avance. La
profondeur de `leadflow.leads.dlq` doit rester nulle ; une valeur qui persiste signale que le
journal ne suit pas.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.deadletter.DeadLetter;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.monitoring.dto.QueueView;
import com.leadflow.monitoring.dto.QueuesView;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QueueServiceTest {

    @Autowired private QueueService service;
    @Autowired private DeadLetterRepository morts;

    @AfterEach
    void nettoie() {
        morts.deleteAll();
    }

    @Test
    void listeLesQuatreFilesDuPipeline() {
        QueuesView vue = service.etatDesFiles();

        assertThat(vue.queues()).extracting(QueueView::name).containsExactlyInAnyOrder(
                RabbitMQConfig.LEADS_QUEUE,
                RabbitMQConfig.QUALIFIED_QUEUE,
                RabbitMQConfig.ROUTED_QUEUE,
                RabbitMQConfig.DLQ_QUEUE);
    }

    @Test
    void remonteProfondeurEtNombreDeConsommateurs() {
        QueueView captees = service.etatDesFiles().queues().stream()
                .filter(file -> file.name().equals(RabbitMQConfig.LEADS_QUEUE))
                .findFirst()
                .orElseThrow();

        assertThat(captees.reachable()).isTrue();
        assertThat(captees.messageCount()).isGreaterThanOrEqualTo(0);
        assertThat(captees.consumerCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void compteLesMortsEnAttenteEnBase() {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue(RabbitMQConfig.QUALIFIED_QUEUE);
        mort.setRoutingKey(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        mort.setPayload("{}");
        mort.setLeadId(UUID.randomUUID());
        mort.setStatus(DeadLetterStatus.PENDING);
        morts.saveAndFlush(mort);

        assertThat(service.etatDesFiles().pendingDeadLetters()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=QueueServiceTest`
Expected: FAIL — `QueueService` n'existe pas.

- [ ] **Step 3: Écrire les DTO**

```java
package com.leadflow.monitoring.dto;

/**
 * Etat d'une file, lu en AMQP.
 *
 * <p>{@code reachable} a false plutot qu'une erreur : un broker momentanement injoignable ne
 * doit pas faire echouer l'ecran qui sert justement a le constater.
 */
public record QueueView(
        String name, boolean reachable, int messageCount, int consumerCount) {
}
```

```java
package com.leadflow.monitoring.dto;

import java.util.List;

/**
 * {@code pendingDeadLetters} vient de la base et non du broker : depuis F6, la table est la
 * source de verite des leads en echec, et la profondeur de la DLQ doit rester nulle. Voir
 * les deux cote a cote est exactement ce qui permet de detecter que le journal ne suit pas.
 */
public record QueuesView(List<QueueView> queues, long pendingDeadLetters) {
}
```

- [ ] **Step 4: Écrire `QueueService`**

```java
package com.leadflow.monitoring;

import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import com.leadflow.monitoring.dto.QueueView;
import com.leadflow.monitoring.dto.QueuesView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

/**
 * Profondeur et nombre de consommateurs, lus par un {@code queue.declare} passif en AMQP.
 *
 * <p>Pas l'API de management : elle demanderait un second jeu d'identifiants, le plugin
 * correspondant, et la resolution du port 15672 dans les tests — que {@code
 * @ServiceConnection} ne cable pas.
 *
 * <p>Le nombre de consommateurs est la mesure la plus lisible d'un listener tombe : un zero
 * sur {@code leadflow.leads.qualified} explique en une seconde pourquoi plus rien n'avance.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private static final List<String> FILES = List.of(
            RabbitMQConfig.LEADS_QUEUE,
            RabbitMQConfig.QUALIFIED_QUEUE,
            RabbitMQConfig.ROUTED_QUEUE,
            RabbitMQConfig.DLQ_QUEUE);

    private final RabbitAdmin admin;
    private final DeadLetterRepository morts;

    public QueueService(RabbitAdmin admin, DeadLetterRepository morts) {
        this.admin = admin;
        this.morts = morts;
    }

    public QueuesView etatDesFiles() {
        List<QueueView> vues = FILES.stream().map(this::etat).toList();
        return new QueuesView(vues, morts.countByStatus(DeadLetterStatus.PENDING));
    }

    private QueueView etat(String nom) {
        try {
            QueueInformation info = admin.getQueueInfo(nom);
            if (info == null) {
                // La file n'existe pas encore cote broker : anomalie de deploiement, pas
                // une erreur d'appel.
                return new QueueView(nom, false, 0, 0);
            }
            return new QueueView(nom, true, info.getMessageCount(), info.getConsumerCount());
        } catch (RuntimeException echec) {
            // Un broker injoignable ne doit pas faire echouer l'ecran qui sert a le
            // constater : la ligne sort « injoignable » et le reste s'affiche.
            log.warn("Etat de la file {} illisible", nom, echec);
            return new QueueView(nom, false, 0, 0);
        }
    }
}
```

Si aucun bean `RabbitAdmin` n'est exposé, l'ajouter dans `RabbitMQConfig` :
`@Bean RabbitAdmin rabbitAdmin(ConnectionFactory cf) { return new RabbitAdmin(cf); }`.
Spring Boot en déclare un dès que `spring-boot-starter-amqp` est présent ; vérifier avant
d'en créer un second.

- [ ] **Step 5: Écrire le contrôleur**

```java
package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.QueuesView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queues")
public class QueueController {

    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    @GetMapping
    public QueuesView files() {
        return service.etatDesFiles();
    }
}
```

- [ ] **Step 6: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=QueueServiceTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring \
        backend/src/test/java/com/leadflow/monitoring/QueueServiceTest.java
git commit -m "feat: profondeur et consommateurs des files, lus en AMQP

RabbitAdmin.getQueueInfo plutot que l'API de management : pas de second jeu
d'identifiants, pas de plugin requis, et rien a resoudre du port 15672 dans les
tests. Un broker injoignable rend une ligne « injoignable » plutot qu'une
erreur : l'ecran qui sert a constater la panne ne doit pas tomber avec elle."
```

---

## Task 11: Flux temps réel

**Files:**
- Create: `backend/src/main/java/com/leadflow/monitoring/stream/StreamEvent.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/stream/LeadStreamBroadcaster.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/stream/LeadStreamController.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/stream/PipelineEventListener.java`
- Create: `backend/src/main/java/com/leadflow/monitoring/stream/package-info.java`
- Create: `backend/src/main/java/com/leadflow/crm/SyncedLeadPublisher.java`
- Modify: `backend/src/main/java/com/leadflow/crm/CrmSyncListener.java`
- Modify: `backend/src/main/java/com/leadflow/config/RabbitMQConfig.java`
- Modify: `backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterListener.java`
- Test: `backend/src/test/java/com/leadflow/monitoring/stream/PipelineEventListenerTest.java`

**Interfaces:**
- Consumes: `leadflow.monitoring.events` (nouvelle file), le journal de T8.
- Produces: `GET /api/stream/leads` en `text/event-stream`, événements `lead` et
  `dead-letter`. Consommé par T15.

**Aucun message n'est volé.** Le monitoring déclare **sa propre file**, liée à l'exchange
`leadflow.leads` sur les mêmes routing keys : un `DirectExchange` livre à **toutes** les
files liées à la clé, et la concurrence entre consommateurs ne joue qu'au sein d'une même
file. Une addition est nécessaire côté pipeline : il n'existe rien après la synchronisation
ERP, donc sans `lead.synced` le flux raterait la fin de l'histoire qu'il raconte.

La file du monitoring n'a **pas de DLX** : un échec d'affichage n'est pas un échec de lead et
n'a rien à faire dans le journal des morts.

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.leadflow.monitoring.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.qualification.QualifiedLeadMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "leadflow.monitoring.stream.listener.enabled=true")
class PipelineEventListenerTest {

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private LeadStreamBroadcaster diffuseur;

    @Test
    void unMessageDuPipelineAtteintUnAbonneSansEtreVoleAuConsommateurMetier() {
        List<Object> recus = new CopyOnWriteArrayList<>();
        SseEmitter emetteur = diffuseur.abonne();
        emetteur.onCompletion(() -> {});

        UUID leadId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.LEADS_EXCHANGE,
                RabbitMQConfig.QUALIFIED_ROUTING_KEY,
                new QualifiedLeadMessage(leadId, UUID.randomUUID(), 42, Instant.now()));

        // La file metier a bien recu sa copie : la preuve qu'un DirectExchange livre a
        // toutes les files liees a la cle, et que l'observateur ne vole rien.
        Message copieMetier = rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000);
        assertThat(copieMetier).isNotNull();

        await().atMost(Duration.ofSeconds(10))
                .until(() -> diffuseur.dernierEvenementDiffuse() != null);
        assertThat(diffuseur.dernierEvenementDiffuse().leadId()).isEqualTo(leadId);
    }

    @Test
    void unEmetteurFermeEstRetireDuRegistre() {
        SseEmitter emetteur = diffuseur.abonne();
        assertThat(diffuseur.nombreDAbonnes()).isPositive();

        emetteur.complete();

        await().atMost(Duration.ofSeconds(5))
                .until(() -> diffuseur.nombreDAbonnes() == 0);
    }
}
```

`dernierEvenementDiffuse()` et `nombreDAbonnes()` existent pour ce test : un flux SSE ne
s'observe pas autrement sans monter un client HTTP complet. Les deux sont documentées comme
telles dans le diffuseur.

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd backend && ./mvnw test -Dtest=PipelineEventListenerTest`
Expected: FAIL — ni la file d'observation, ni le diffuseur n'existent.

- [ ] **Step 3: Compléter `RabbitMQConfig`**

```java
    /** Sortie de la synchronisation ERP. Aucun consommateur metier : le monitoring seul. */
    public static final String SYNCED_ROUTING_KEY = "lead.synced";

    /**
     * File d'observation du monitoring. Distincte des files metier : un DirectExchange
     * livre a TOUTES les files liees a une cle, et la concurrence entre consommateurs ne
     * joue qu'au sein d'une meme file. Le monitoring observe donc sans qu'aucune ligne du
     * routage ou de la qualification ne bouge.
     */
    public static final String MONITORING_QUEUE = "leadflow.monitoring.events";

    /**
     * <b>Pas de DLX.</b> Un echec d'affichage n'est pas un echec de lead et n'a rien a faire
     * dans le journal des morts.
     */
    @Bean
    Queue monitoringEventsQueue() {
        return QueueBuilder.durable(MONITORING_QUEUE).build();
    }

    @Bean
    List<Binding> monitoringEventsBindings(
            Queue monitoringEventsQueue, DirectExchange leadsExchange) {
        return Stream.of(
                        LEADS_ROUTING_KEY,
                        QUALIFIED_ROUTING_KEY,
                        ROUTED_ROUTING_KEY,
                        SYNCED_ROUTING_KEY)
                .map(cle -> BindingBuilder.bind(monitoringEventsQueue).to(leadsExchange).with(cle))
                .toList();
    }
```

Spring AMQP déclare les bindings d'une `List<Binding>` exposée en bean ; si la version ne le
fait pas, écrire quatre beans `Binding` nommés plutôt qu'une boucle.

Ajouter `"com.leadflow.crm"` à `PAQUETS_DE_CONFIANCE` — `SyncedLeadMessage` y vit, et la
correspondance est exacte, ni préfixe ni joker.

- [ ] **Step 4: Publier `lead.synced`**

`crm/SyncedLeadMessage.java` :

```java
package com.leadflow.crm;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.synced}. Fin de l'histoire du lead.
 *
 * <p>Aucun consommateur metier ne s'y abonne : la cle n'est liee qu'a la file du
 * monitoring. Le pipeline publie, qui ecoute ne le regarde pas — et aucun rejeu de cette
 * cle ne peut donc declencher quoi que ce soit.
 */
public record SyncedLeadMessage(
        UUID leadId, UUID clientId, String providerId, Instant syncedAt) {
}
```

`crm/SyncedLeadPublisher.java`, sur le modèle exact de `RoutedLeadPublisher` :

```java
package com.leadflow.crm;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publie la fin du parcours. Sans elle, le flux du dashboard montrerait un lead fige en
 * {@code ROUTED} jusqu'au prochain rechargement : il raterait la fin de l'histoire qu'il
 * raconte, {@code CrmSyncListener} ecrivant {@code SYNCED} puis se taisant.
 *
 * <p>L'echec de publication est logue et jamais relance : le lead est synchronise, et faire
 * echouer le consommateur renverrait en DLQ un message deja traite avec succes. Meme regle
 * qu'en F3 et F4.
 */
@Component
public class SyncedLeadPublisher {

    private static final Logger log = LoggerFactory.getLogger(SyncedLeadPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public SyncedLeadPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publie(UUID leadId, UUID clientId, String providerId) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.SYNCED_ROUTING_KEY,
                    new SyncedLeadMessage(leadId, clientId, providerId, Instant.now()));
        } catch (AmqpException echec) {
            log.warn("Publication de lead.synced pour {} en echec", leadId, echec);
        }
    }
}
```

Et dans `CrmSyncListener`, après `writer.marqueSynchronise(message.leadId())` :

```java
        publieur.publie(message.leadId(), message.clientId(), null);
```

Le `providerId` reste `null` tant que `CrmSyncService.synchronise` ne le rend pas ; si son
retour le porte déjà, le passer plutôt que `null` — le flux n'en dépend pas, l'écran de
détail donnant l'information complète.

- [ ] **Step 5: Écrire `StreamEvent` et le diffuseur**

```java
package com.leadflow.monitoring.stream;

import java.time.Instant;
import java.util.UUID;

/**
 * Charge utile volontairement maigre : l'identifiant et l'etat suffisent a animer une ligne,
 * le detail se charge au clic. Une connexion longue ne doit pas diffuser en continu des
 * e-mails et des messages de prospects.
 */
public record StreamEvent(
        UUID leadId, UUID clientId, String status, Integer score, UUID salesRepId,
        Instant occurredAt) {
}
```

```java
package com.leadflow.monitoring.stream;

import com.leadflow.monitoring.dto.DeadLetterView;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registre des emetteurs SSE ouverts.
 *
 * <p><b>Mono-instance</b>, comme {@code PendingEventRelay} et le tour de role de F4 : le
 * registre est en memoire, donc a deux instances un abonne de l'une ne verrait pas ce que
 * traite l'autre. La reponse serait un exchange fanout et une file exclusive par instance —
 * une evolution, pas un correctif.
 *
 * <p>Le commentaire de maintien toutes les 20 secondes n'est pas cosmetique : sans trafic,
 * un proxy coupe une connexion inactive et l'ecran se fige <b>sans le dire</b>.
 */
@Component
public class LeadStreamBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LeadStreamBroadcaster.class);

    private final List<SseEmitter> emetteurs = new CopyOnWriteArrayList<>();
    private final Duration expiration;

    /** Dernier evenement diffuse, expose pour les tests : un flux SSE ne s'observe pas autrement. */
    private volatile StreamEvent dernier;

    public LeadStreamBroadcaster(com.leadflow.config.MonitoringProperties properties) {
        this.expiration = properties.stream().emitterTimeout();
    }

    public SseEmitter abonne() {
        SseEmitter emetteur = new SseEmitter(expiration.toMillis());
        // Les trois retraits sont indispensables : un emetteur mort laisse dans le registre
        // fait echouer chaque diffusion suivante.
        emetteur.onCompletion(() -> emetteurs.remove(emetteur));
        emetteur.onTimeout(() -> emetteurs.remove(emetteur));
        emetteur.onError(erreur -> emetteurs.remove(emetteur));
        emetteurs.add(emetteur);
        return emetteur;
    }

    public void diffuseLead(StreamEvent evenement) {
        this.dernier = evenement;
        diffuse("lead", evenement);
    }

    /** Alimente le meme flux depuis le journal, en memoire : c'est le meme processus. */
    public void diffuseMort(DeadLetterView mort) {
        diffuse("dead-letter", mort);
    }

    @Scheduled(fixedDelayString = "${leadflow.monitoring.stream.heartbeat-interval}")
    void maintientLesConnexions() {
        emetteurs.forEach(emetteur -> {
            try {
                emetteur.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ferme) {
                emetteurs.remove(emetteur);
            }
        });
    }

    private void diffuse(String nom, Object charge) {
        emetteurs.forEach(emetteur -> {
            try {
                emetteur.send(SseEmitter.event().name(nom).data(charge));
            } catch (IOException | IllegalStateException ferme) {
                // Un client parti n'est pas une erreur : on le retire et on continue.
                emetteurs.remove(emetteur);
            }
        });
    }

    public int nombreDAbonnes() {
        return emetteurs.size();
    }

    public StreamEvent dernierEvenementDiffuse() {
        return dernier;
    }
}
```

- [ ] **Step 6: Écrire le consommateur d'observation et le contrôleur**

```java
package com.leadflow.monitoring.stream;

import com.leadflow.capture.CapturedLeadMessage;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.crm.SyncedLeadMessage;
import com.leadflow.qualification.QualifiedLeadMessage;
import com.leadflow.routing.RoutedLeadMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Traduit les contrats de file du pipeline en evenements de flux. Il n'ecrit rien et ne lit
 * aucune table : c'est l'observateur au sens strict de la regle 3.1.
 *
 * <p>Bean conditionnel comme les quatre autres consommateurs du projet, et retire dans la
 * suite de tests sauf la ou elle l'eprouve.
 */
@Component
@ConditionalOnProperty(
        name = "leadflow.monitoring.stream.listener.enabled", matchIfMissing = true)
public class PipelineEventListener {

    private final LeadStreamBroadcaster diffuseur;

    public PipelineEventListener(LeadStreamBroadcaster diffuseur) {
        this.diffuseur = diffuseur;
    }

    @RabbitListener(queues = RabbitMQConfig.MONITORING_QUEUE)
    public void recoit(Object message) {
        switch (message) {
            case CapturedLeadMessage capte -> diffuseur.diffuseLead(new StreamEvent(
                    null, capte.clientId(), "CAPTURED", null, null, capte.receivedAt()));
            case QualifiedLeadMessage qualifie -> diffuseur.diffuseLead(new StreamEvent(
                    qualifie.leadId(), qualifie.clientId(), "QUALIFIED", qualifie.score(),
                    null, qualifie.qualifiedAt()));
            case RoutedLeadMessage route -> diffuseur.diffuseLead(new StreamEvent(
                    route.leadId(), route.clientId(), "ROUTED", null, route.salesRepId(),
                    route.routedAt()));
            case SyncedLeadMessage synchronise -> diffuseur.diffuseLead(new StreamEvent(
                    synchronise.leadId(), synchronise.clientId(), "SYNCED", null, null,
                    synchronise.syncedAt()));
            // Un contrat de file inconnu ne fait pas tomber l'observateur : une nouvelle
            // etape du pipeline ne doit pas casser l'ecran avant d'y etre cablee.
            default -> { }
        }
    }
}
```

Vérifier le nom exact des accesseurs de `CapturedLeadMessage` avant d'écrire ce `switch` :
il date de F2 et ne porte peut-être pas `receivedAt`.

```java
package com.leadflow.monitoring.stream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE plutot que WebSocket : le flux est purement descendant, rien ne remonte du navigateur.
 *
 * <p>Cote client, <b>pas d'{@code EventSource}</b> : il ne sait pas poser d'en-tete
 * {@code Authorization}, et passer le jeton en parametre d'URL le ferait apparaitre dans les
 * journaux d'acces. Le service Angular lit le flux par {@code fetch} et decoupe les trames
 * lui-meme (T15).
 */
@RestController
@RequestMapping("/api/stream")
public class LeadStreamController {

    private final LeadStreamBroadcaster diffuseur;

    public LeadStreamController(LeadStreamBroadcaster diffuseur) {
        this.diffuseur = diffuseur;
    }

    @GetMapping(value = "/leads", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter flux() {
        return diffuseur.abonne();
    }
}
```

- [ ] **Step 7: Brancher le journal sur le flux**

Dans `DeadLetterListener`, après `journal.enregistre(mort)`, diffuser la ligne écrite. Le
diffuseur est injecté en plus ; il ne repasse pas par le broker, c'est le même processus.

```java
        DeadLetter ecrite = journal.enregistre(mort);
        diffuseur.diffuseMort(vue(ecrite));
```

Construire la `DeadLetterView` avec `clientName` à `null` : le flux est maigre par principe,
et l'écran recharge la liste au clic.

- [ ] **Step 8: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=PipelineEventListenerTest` puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/monitoring/stream \
        backend/src/main/java/com/leadflow/crm \
        backend/src/main/java/com/leadflow/config/RabbitMQConfig.java \
        backend/src/main/java/com/leadflow/monitoring/deadletter/DeadLetterListener.java \
        backend/src/test/java/com/leadflow/monitoring/stream
git commit -m "feat: flux SSE alimente par une file d'observation propre

Le monitoring declare sa file et la lie aux memes cles que le pipeline : un
DirectExchange livre a toutes les files liees a une cle, donc l'observateur ne
vole aucun message — le test l'asserte en lisant les deux copies. lead.synced
est ajoute parce que rien n'existait apres la synchronisation ERP : sans lui le
flux figerait le lead en ROUTED et raterait la fin de son parcours. La file
d'observation n'a pas de DLX, un echec d'affichage n'etant pas un echec de lead."
```

---

## Task 12: Filets de republication du pipeline

**Files:**
- Create: `backend/src/main/java/com/leadflow/qualification/QualifiedLeadRelay.java`
- Create: `backend/src/main/java/com/leadflow/routing/RoutedLeadRelay.java`
- Modify: `backend/src/main/java/com/leadflow/qualification/LeadRepository.java`
- Modify: `backend/src/main/java/com/leadflow/qualification/QualifiedLeadPublisher.java` (Javadoc)
- Modify: `backend/src/main/java/com/leadflow/routing/RoutedLeadPublisher.java` (Javadoc)
- Test: `backend/src/test/java/com/leadflow/qualification/QualifiedLeadRelayTest.java`
- Test: `backend/src/test/java/com/leadflow/routing/RoutedLeadRelayTest.java`

**Interfaces:**
- Consumes: `LeadRepository` (F3), `DeadLetterRepository` (T7), les deux publieurs existants.
- Produces: rien de nouveau à l'extérieur — les deux dettes de F3 et F4 sont soldées.

Les deux filets **ne vivent pas dans `monitoring/`** : ils publient sur le pipeline, donc ils
appartiennent aux packages qui possèdent ces publications (règle 3.1). Le balayage n'est
borné que depuis F4, quand un lead quitte réellement `QUALIFIED` puis `ROUTED`.

**Le garde-fou est le cœur de la tâche.** Sans lui, un filet transforme un échec permanent en
inondation : un client sans commercial actif fait lever `AssignmentException`, le lead reste
`QUALIFIED`, le message part en DLQ — et le filet le republie toutes les 30 secondes en
fabriquant une ligne de journal à chaque tour. **Le filet ignore donc les leads qui ont déjà
une ligne `dead_letter` en `PENDING`**, et le lead redevient éligible dès que l'opérateur l'a
rejoué ou écarté. C'est la même distinction que F2 a faite entre `FAILED` et `DISCARDED`.

C'est aussi le critère de recette 7.

- [ ] **Step 1: Écrire les tests qui échouent**

```java
package com.leadflow.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.monitoring.deadletter.DeadLetter;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QualifiedLeadRelayTest {

    @Autowired private QualifiedLeadRelay filet;
    @Autowired private LeadRepository leads;
    @Autowired private DeadLetterRepository morts;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void nettoie() {
        morts.deleteAll();
        leads.deleteAll();
        while (rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 100) != null) {
            // on vide la file entre deux tests
        }
    }

    @Test
    void republieUnLeadQualifieAssezVieux() {
        UUID leadId = creeUnLeadQualifieVieuxDe(java.time.Duration.ofMinutes(10));

        filet.republieLesNonRoutes();

        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000)).isNotNull();
        assertThat(leadId).isNotNull();
    }

    @Test
    void ignoreUnLeadTropRecent() {
        creeUnLeadQualifieVieuxDe(java.time.Duration.ofSeconds(5));

        filet.republieLesNonRoutes();

        // Un delai plus long qu'une publication normale : le filet ne double jamais un
        // envoi en cours.
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 500)).isNull();
    }

    @Test
    void ignoreUnLeadDejaMortEtEnAttenteDActionHumaine() {
        UUID leadId = creeUnLeadQualifieVieuxDe(java.time.Duration.ofMinutes(10));
        morts.saveAndFlush(mortEnAttente(leadId));

        filet.republieLesNonRoutes();

        // Critere de recette 7 : sans ce garde-fou, un client sans commercial actif ferait
        // republier toutes les 30 secondes et grossir le journal a chaque tour.
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 500)).isNull();
    }

    @Test
    void republieDeNouveauUneFoisLaMortEcartee() {
        UUID leadId = creeUnLeadQualifieVieuxDe(java.time.Duration.ofMinutes(10));
        DeadLetter mort = morts.saveAndFlush(mortEnAttente(leadId));
        mort.setStatus(DeadLetterStatus.DISCARDED);
        morts.saveAndFlush(mort);

        filet.republieLesNonRoutes();

        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUALIFIED_QUEUE, 5000)).isNotNull();
    }

    private UUID creeUnLeadQualifieVieuxDe(java.time.Duration age) {
        Lead lead = new Lead();
        lead.setClientId(UUID.randomUUID());
        lead.setRawEventId(UUID.randomUUID());
        lead.setEmail(UUID.randomUUID() + "@test.fr");
        lead.setScore(42);
        lead.setStatus(LeadStatus.QUALIFIED);
        UUID id = leads.saveAndFlush(lead).getId();
        // created_at est pose par @PrePersist : le vieillir en SQL est le seul moyen de
        // tester le seuil sans attendre.
        jdbc.update("update lead set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(age)), id);
        return id;
    }

    private DeadLetter mortEnAttente(UUID leadId) {
        DeadLetter mort = new DeadLetter();
        mort.setOriginQueue(RabbitMQConfig.QUALIFIED_QUEUE);
        mort.setRoutingKey(RabbitMQConfig.QUALIFIED_ROUTING_KEY);
        mort.setPayload("{}");
        mort.setLeadId(leadId);
        mort.setStatus(DeadLetterStatus.PENDING);
        return mort;
    }
}
```

`RoutedLeadRelayTest` est le symétrique exact : statut `ROUTED`, file
`RabbitMQConfig.ROUTED_QUEUE`, méthode `republieLesNonSynchronises()`.

- [ ] **Step 2: Vérifier que les tests échouent**

Run: `cd backend && ./mvnw test -Dtest=QualifiedLeadRelayTest+RoutedLeadRelayTest`
Expected: FAIL — les deux filets n'existent pas.

- [ ] **Step 3: Compléter `LeadRepository`**

```java
    /**
     * Leads restes dans un etat depuis plus longtemps que le seuil. Balayage borne depuis
     * F4 : un lead quitte reellement QUALIFIED puis ROUTED, ce qui n'etait pas le cas avant
     * et rendait ce filet impossible a ecrire sans republier la table entiere en boucle.
     */
    List<Lead> findByStatusAndCreatedAtBefore(LeadStatus status, Instant limite);
```

- [ ] **Step 4: Écrire `QualifiedLeadRelay`**

```java
package com.leadflow.qualification;

import com.leadflow.config.MonitoringProperties;
import com.leadflow.monitoring.deadletter.DeadLetterRepository;
import com.leadflow.monitoring.deadletter.DeadLetterStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet de republication de {@code lead.qualified}, dette contractee par F3 et echue ici.
 *
 * <p>Il vit dans {@code qualification/} et non dans {@code monitoring/} : il publie sur le
 * pipeline, donc il appartient au package qui possede cette publication. L'observateur
 * n'ecrit rien et ne publie rien d'autre qu'un rejeu de mort.
 *
 * <p><b>Garde-fou.</b> Un lead qui porte deja une mort {@code PENDING} n'est pas republie :
 * un echec deja constate et presente a un humain n'est plus un echec transitoire. Sans cela,
 * un client sans commercial actif ferait boucler le filet toutes les 30 secondes en
 * fabriquant une ligne de journal a chaque tour. Le lead redevient eligible des que
 * l'operateur a rejoue ou ecarte la mort — meme distinction que F2 entre {@code FAILED} et
 * {@code DISCARDED}.
 *
 * <p><b>Mono-instance</b>, comme {@code PendingEventRelay}, et pour la meme raison.
 *
 * <p>Le couplage vers une table de {@code monitoring/} est a contre-sens de la regle 3.1 ;
 * il est assume (12.5). L'alternative — un marqueur d'echec definitif sur la ligne
 * {@code lead} — demanderait une colonne de plus et une transition d'etat que personne
 * n'ecrit aujourd'hui.
 */
@Component
public class QualifiedLeadRelay {

    private static final Logger log = LoggerFactory.getLogger(QualifiedLeadRelay.class);

    private final LeadRepository leads;
    private final DeadLetterRepository morts;
    private final QualifiedLeadPublisher publieur;
    private final MonitoringProperties properties;

    public QualifiedLeadRelay(
            LeadRepository leads,
            DeadLetterRepository morts,
            QualifiedLeadPublisher publieur,
            MonitoringProperties properties) {
        this.leads = leads;
        this.morts = morts;
        this.publieur = publieur;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${leadflow.monitoring.relay.interval}")
    public void republieLesNonRoutes() {
        Instant limite = Instant.now().minus(properties.relay().qualifiedAfter());
        List<Lead> candidats =
                leads.findByStatusAndCreatedAtBefore(LeadStatus.QUALIFIED, limite);

        for (Lead lead : candidats) {
            if (morts.existsByLeadIdAndStatus(lead.getId(), DeadLetterStatus.PENDING)) {
                continue;
            }
            try {
                publieur.publie(lead);
                log.info("Lead qualifie {} republie par le filet", lead.getId());
            } catch (RuntimeException echec) {
                // Un lead empoisonne n'emporte pas le lot : le filet s'active precisement
                // quand l'environnement va mal.
                log.warn("Republication du lead {} en echec, on continue", lead.getId(), echec);
            }
        }
    }
}
```

- [ ] **Step 5: Écrire `RoutedLeadRelay`**

Symétrique : `LeadStatus.ROUTED`, `properties.relay().routedAfter()`,
`RoutedLeadPublisher`, méthode `republieLesNonSynchronises()`. Même garde-fou, même Javadoc
sur le couplage assumé.

- [ ] **Step 6: Corriger les Javadoc des deux publieurs**

`QualifiedLeadPublisher` et `RoutedLeadPublisher` annoncent tous deux « dette assumée : aucun
filet de republication » et la reportent à F6. Remplacer ces paragraphes par un renvoi vers
le filet qui existe désormais — une dette soldée qui reste écrite dans le code est un
mensonge que la prochaine session croira.

- [ ] **Step 7: Vérifier et committer**

Run: `cd backend && ./mvnw test -Dtest=QualifiedLeadRelayTest+RoutedLeadRelayTest`
puis `./mvnw test`
Expected: PASS.

```bash
git add backend/src/main/java/com/leadflow/qualification \
        backend/src/main/java/com/leadflow/routing \
        backend/src/test/java/com/leadflow/qualification/QualifiedLeadRelayTest.java \
        backend/src/test/java/com/leadflow/routing/RoutedLeadRelayTest.java
git commit -m "feat: filets de republication de lead.qualified et lead.routed

Les deux dettes de F3 et F4 sont soldees. Les filets vivent dans les packages
qui possedent ces publications, pas dans monitoring : l'observateur ne publie
rien sur le pipeline. Le garde-fou est le point important — un lead portant
deja une mort PENDING n'est pas republie, sans quoi un client sans commercial
actif ferait boucler le filet toutes les trente secondes en grossissant le
journal a chaque tour."
```

---

## Task 13: Socle frontend — Material, authentification, coquille

**Files:**
- Modify: `frontend/package.json` (dépendance `@angular/material`)
- Create: `frontend/src/app/core/models/page-response.ts`
- Create: `frontend/src/app/core/models/lead.ts`
- Create: `frontend/src/app/core/models/monitoring.ts`
- Create: `frontend/src/app/core/auth/auth.ts`
- Create: `frontend/src/app/core/auth/auth-interceptor.ts`
- Create: `frontend/src/app/core/auth/auth-guard.ts`
- Create: `frontend/src/app/features/login/login.ts` + `.html` + `.scss`
- Modify: `frontend/src/app/app.config.ts`, `app.routes.ts`, `app.ts`, `app.html`, `app.scss`
- Modify: `frontend/src/styles.scss`
- Test: `frontend/src/app/core/auth/auth.spec.ts`

**Interfaces:**
- Consumes: `POST /api/auth/login` (T1).
- Produces: `Auth` (signal du jeton), `authInterceptor`, `authGuard`, les interfaces
  TypeScript des DTO. **T13 précède toutes les autres tâches frontend.**

Le jeton va en `localStorage` : il doit survivre au rechargement, ce qu'une session de 8
heures rend nécessaire. Le choix l'expose à une XSS ; la contrepartie est qu'il n'y a ni
cookie ni CSRF à gérer sur une API `STATELESS`. L'intercepteur pose le `Bearer`, et sur `401`
il vide le jeton et renvoie à `/login`.

- [ ] **Step 1: Ajouter Angular Material**

```bash
cd frontend && npm install @angular/material@^20.3.0 @angular/cdk@^20.3.0
```

**Une seule dépendance ajoutée.** Pas de Chart.js ni de `ngx-charts` : aucune
`peerDependency` à faire correspondre à Angular 20.3. Conséquence acceptée — l'écran de
statistiques affichera des compteurs et des `mat-progress-bar`, pas de camembert.

Thème Material 3 défini une fois dans `src/styles.scss` :

```scss
@use '@angular/material' as mat;

html {
  color-scheme: light;
  @include mat.theme((color: (primary: mat.$azure-palette), typography: Roboto, density: 0));
}

body {
  margin: 0;
  background: var(--mat-sys-surface);
  color: var(--mat-sys-on-surface);
}
```

- [ ] **Step 2: Écrire le test qui échoue**

`frontend/src/app/core/auth/auth.spec.ts` :

```ts
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { Auth } from './auth';
import { authInterceptor } from './auth-interceptor';

describe('Auth', () => {
  let auth: Auth;
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('stocke le jeton et expose un signal de connexion', () => {
    auth.login('operateur', 'secret').subscribe();

    httpMock.expectOne('/api/auth/login').flush({
      token: 'jeton-de-test',
      expiresAt: '2026-08-24T00:00:00Z',
    });

    expect(auth.token()).toBe('jeton-de-test');
    expect(auth.estConnecte()).toBeTrue();
    // Survit au rechargement : une session de huit heures l'exige.
    expect(localStorage.getItem('leadflow.token')).toBe('jeton-de-test');
  });

  it("pose l'en-tete Authorization sur les appels API", () => {
    auth.applique('jeton-de-test', '2026-08-24T00:00:00Z');

    http.get('/api/leads').subscribe();

    const requete = httpMock.expectOne('/api/leads');
    expect(requete.request.headers.get('Authorization')).toBe('Bearer jeton-de-test');
    requete.flush({});
  });

  it("ne pose pas d'en-tete sur la connexion elle-meme", () => {
    auth.applique('jeton-de-test', '2026-08-24T00:00:00Z');

    http.post('/api/auth/login', {}).subscribe();

    const requete = httpMock.expectOne('/api/auth/login');
    expect(requete.request.headers.has('Authorization')).toBeFalse();
    requete.flush({});
  });

  it('vide le jeton et renvoie a la connexion sur 401', () => {
    auth.applique('jeton-expire', '2026-08-24T00:00:00Z');

    http.get('/api/leads').subscribe({ error: () => {} });
    httpMock.expectOne('/api/leads').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.token()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
```

- [ ] **Step 3: Vérifier que le test échoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `Auth` et `authInterceptor` n'existent pas.

- [ ] **Step 4: Écrire les modèles**

`core/models/page-response.ts` :

```ts
/** Calquee sur le record PageResponse du backend, ecrite une fois pour toutes les listes. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
```

`core/models/lead.ts` — `LeadSummary`, `LeadDetail`, `SalesRepView`, `SyncAttemptView`,
`RawEventView`, calqués sur les records de T2 et T3, champ pour champ.

`core/models/monitoring.ts` — `StatsView`, `QueueView`, `QueuesView`, `DeadLetterView`,
`ConnectorView`, `ClientSummary`, `SalesRepSummary`, plus :

```ts
export type LeadStatus = 'QUALIFIED' | 'ROUTED' | 'SYNCED' | 'REJECTED' | 'FAILED';
export type DeadLetterStatus = 'PENDING' | 'REPLAYED' | 'DISCARDED';
```

- [ ] **Step 5: Écrire `Auth`**

```ts
import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { tap } from 'rxjs';

const CLE_JETON = 'leadflow.token';
const CLE_EXPIRATION = 'leadflow.expiresAt';

interface LoginResponse {
  token: string;
  expiresAt: string;
}

/**
 * Etat d'authentification du dashboard.
 *
 * Le jeton vit dans localStorage : il doit survivre au rechargement, ce qu'une session de
 * huit heures rend necessaire. Le choix l'expose a une XSS ; la contrepartie est qu'il n'y
 * a ni cookie ni CSRF a gerer sur une API STATELESS. Pas de rafraichissement : a
 * l'expiration, retour a l'ecran de connexion.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);

  readonly token = signal<string | null>(localStorage.getItem(CLE_JETON));
  readonly estConnecte = computed(() => this.token() !== null);

  login(username: string, password: string) {
    return this.http
      .post<LoginResponse>('/api/auth/login', { username, password })
      .pipe(tap((reponse) => this.applique(reponse.token, reponse.expiresAt)));
  }

  applique(token: string, expiresAt: string): void {
    localStorage.setItem(CLE_JETON, token);
    localStorage.setItem(CLE_EXPIRATION, expiresAt);
    this.token.set(token);
  }

  vide(): void {
    localStorage.removeItem(CLE_JETON);
    localStorage.removeItem(CLE_EXPIRATION);
    this.token.set(null);
  }
}
```

- [ ] **Step 6: Écrire l'intercepteur et le garde**

```ts
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth';

/**
 * Pose le Bearer sur tous les appels sauf la connexion — y ajouter un jeton expire ferait
 * echouer la seule requete capable d'en obtenir un neuf.
 *
 * Sur 401, le jeton est vide et l'utilisateur renvoye a /login : c'est le seul traitement
 * possible sans mecanisme de rafraichissement, choix assume en 3.3.
 */
export const authInterceptor: HttpInterceptorFn = (requete, suivant) => {
  const auth = inject(Auth);
  const router = inject(Router);
  const jeton = auth.token();

  const envoyee =
    jeton && !requete.url.includes('/api/auth/login')
      ? requete.clone({ setHeaders: { Authorization: `Bearer ${jeton}` } })
      : requete;

  return suivant(envoyee).pipe(
    catchError((erreur: HttpErrorResponse) => {
      if (erreur.status === 401) {
        auth.vide();
        router.navigate(['/login']);
      }
      return throwError(() => erreur);
    }),
  );
};
```

```ts
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

export const authGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);
  return auth.estConnecte() ? true : router.createUrlTree(['/login']);
};
```

- [ ] **Step 7: Écrire l'écran de connexion**

`features/login/login.ts` — formulaire réactif, deux champs, message d'erreur unique :

```ts
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Auth } from '../../core/auth/auth';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  readonly erreur = signal<string | null>(null);
  readonly enCours = signal(false);

  readonly formulaire = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  soumet(): void {
    if (this.formulaire.invalid) {
      return;
    }
    this.enCours.set(true);
    const { username, password } = this.formulaire.getRawValue();
    this.auth.login(username, password).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      // Message unique : le serveur ne distingue pas identifiant inconnu et mot de passe
      // faux, et l'ecran ne doit pas inventer la distinction.
      error: () => {
        this.erreur.set('Identifiants refuses');
        this.enCours.set(false);
      },
    });
  }
}
```

- [ ] **Step 8: Câbler routes, providers et coquille**

`app.config.ts` gagne l'intercepteur et les animations :

```ts
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    provideAnimationsAsync(),
```

`app.routes.ts` : `/login` libre, `/leads/:id` ajoutée, les quatre autres sous `authGuard` :

```ts
  {
    path: 'login',
    title: 'Connexion',
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
  },
  {
    path: 'leads/:id',
    title: 'Detail du lead',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/leads/lead-detail/lead-detail').then((m) => m.LeadDetail),
  },
```

`app.html` devient `mat-toolbar` + `mat-sidenav`, avec le bouton de déconnexion et le nom de
l'opérateur ; la barre et le menu ne s'affichent que si `auth.estConnecte()`, sinon l'écran
de connexion apparaîtrait dans le cadre de l'application.

- [ ] **Step 9: Vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
puis `npm run build`
Expected: PASS, et le build montre les chunks lazy — un par feature, plus `login`.

```bash
git add frontend/package.json frontend/package-lock.json frontend/src
git commit -m "feat: socle du dashboard — Material, jeton, garde et coquille

Le jeton vit dans localStorage : il doit survivre au rechargement, ce qu'une
session de huit heures impose, et l'API etant STATELESS il n'y a ni cookie ni
CSRF a gerer en echange. L'intercepteur ne pose rien sur /api/auth/login — y
ajouter un jeton expire ferait echouer la seule requete capable d'en rendre un
neuf. Une seule dependance ajoutee, Angular Material, donc aucune
peerDependency de bibliotheque de graphiques a faire correspondre."
```

---

## Task 14: Écran Leads et détail

**Files:**
- Create: `frontend/src/app/core/api/lead-api.ts`
- Create: `frontend/src/app/core/api/client-api.ts`
- Create: `frontend/src/app/shared/status-badge/status-badge.ts` + `.html` + `.scss`
- Modify: `frontend/src/app/features/leads/leads.ts` + `.html` + `.scss`
- Create: `frontend/src/app/features/leads/lead-detail/lead-detail.ts` + `.html` + `.scss`
- Test: `frontend/src/app/core/api/lead-api.spec.ts`

**Interfaces:**
- Consumes: `GET /api/leads`, `GET /api/leads/{id}` (T2, T3), `GET /api/clients` (T4).
- Produces: l'écran de liste et la route `/leads/:id`.

**Pagination et tri côté serveur.** `mat-table` + `mat-paginator` + `matSort` en mode
serveur : `[length]` alimenté par `totalElements`, `(page)` et `(sortChange)` déclenchant une
nouvelle requête. Il faut l'écrire explicitement parce que la plupart des exemples branchent
un `MatTableDataSource` sur un tableau complet — appliqué à une page déjà paginée par le
serveur, cela paginerait en mémoire une page et afficherait des totaux faux.

- [ ] **Step 1: Écrire le test qui échoue**

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LeadApi } from './lead-api';

describe('LeadApi', () => {
  let api: LeadApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(LeadApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('appelle un chemin relatif et transmet pagination et tri', () => {
    api.liste({ page: 2, size: 25, sort: 'createdAt,desc' }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    expect(requete.request.params.get('page')).toBe('2');
    expect(requete.request.params.get('size')).toBe('25');
    expect(requete.request.params.get('sort')).toBe('createdAt,desc');
    requete.flush({ content: [], page: 2, size: 25, totalElements: 0, totalPages: 0 });
  });

  it("n'envoie pas les filtres absents", () => {
    api.liste({ page: 0, size: 25, sort: 'createdAt,desc', clientId: undefined, q: '' }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    // Un filtre vide envoye au serveur produirait un predicat inutile, et « q= » vide
    // ferait un LIKE '%%' sur toute la table.
    expect(requete.request.params.has('clientId')).toBeFalse();
    expect(requete.request.params.has('q')).toBeFalse();
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('repete le parametre status pour un filtre multiple', () => {
    api.liste({ page: 0, size: 25, sort: 'createdAt,desc', status: ['QUALIFIED', 'ROUTED'] }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/leads');
    expect(requete.request.params.getAll('status')).toEqual(['QUALIFIED', 'ROUTED']);
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });
});
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `LeadApi` n'existe pas.

- [ ] **Step 3: Écrire `LeadApi` et `ClientApi`**

```ts
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { LeadDetail, LeadSummary } from '../models/lead';
import { PageResponse } from '../models/page-response';
import { LeadStatus } from '../models/monitoring';

export interface LeadQuery {
  page: number;
  size: number;
  sort: string;
  clientId?: string;
  status?: LeadStatus[];
  intent?: string;
  intentSource?: string;
  salesRepId?: string;
  minScore?: number;
  from?: string;
  to?: string;
  q?: string;
}

/**
 * Chemins relatifs, jamais d'URL absolue : environment.apiBaseUrl est vide dans les deux
 * environnements — en dev le proxy renvoie /api vers :8080, en production le dashboard est
 * servi derriere le meme domaine que l'API.
 */
@Injectable({ providedIn: 'root' })
export class LeadApi {
  private readonly http = inject(HttpClient);

  liste(requete: LeadQuery) {
    let params = new HttpParams()
      .set('page', requete.page)
      .set('size', requete.size)
      .set('sort', requete.sort);

    // Un filtre absent ne doit pas partir : cote serveur, chaque parametre present ajoute
    // un predicat, et un « q » vide ferait un LIKE '%%' sur toute la table.
    for (const [cle, valeur] of Object.entries(requete)) {
      if (['page', 'size', 'sort'].includes(cle) || valeur === undefined || valeur === '') {
        continue;
      }
      if (Array.isArray(valeur)) {
        valeur.forEach((element) => (params = params.append(cle, element)));
      } else {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<PageResponse<LeadSummary>>('/api/leads', { params });
  }

  detail(id: string) {
    return this.http.get<LeadDetail>(`/api/leads/${id}`);
  }
}
```

`ClientApi` : `clients()` et `commerciaux(clientId)`, deux `GET` sans paramètre.

- [ ] **Step 4: Écrire le badge de statut partagé**

Un composant `shared/status-badge/` qui prend un `status` en entrée et rend une pastille
colorée. Les cinq statuts de lead et les trois du journal passent par lui : la couleur d'un
statut est une décision d'interface, et la répéter dans quatre écrans la ferait diverger.

- [ ] **Step 5: Écrire l'écran de liste**

```ts
import { Component, inject, signal } from '@angular/core';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { LeadApi, LeadQuery } from '../../core/api/lead-api';
import { LeadSummary } from '../../core/models/lead';

/**
 * Table en <b>mode serveur</b> : la page affichee est celle que l'API a rendue.
 *
 * Ne pas brancher un MatTableDataSource sur `content` : applique a une page deja paginee
 * par le serveur, il paginerait en memoire une page de vingt-cinq lignes et afficherait un
 * total faux. `[length]` vient de totalElements, et chaque (page) ou (sortChange) declenche
 * une requete.
 */
@Component({
  selector: 'app-leads',
  imports: [MatTableModule, MatPaginatorModule, MatSortModule /* ... */],
  templateUrl: './leads.html',
  styleUrl: './leads.scss',
})
export class Leads {
  private readonly api = inject(LeadApi);

  readonly colonnes = [
    'createdAt', 'clientName', 'companyName', 'email',
    'detectedIntent', 'score', 'status', 'salesRepName',
  ];

  readonly lignes = signal<LeadSummary[]>([]);
  readonly total = signal(0);
  readonly enCours = signal(false);

  private requete: LeadQuery = { page: 0, size: 25, sort: 'createdAt,desc' };

  ngOnInit(): void {
    this.charge();
  }

  changePage(evenement: PageEvent): void {
    this.requete = { ...this.requete, page: evenement.pageIndex, size: evenement.pageSize };
    this.charge();
  }

  changeTri(tri: Sort): void {
    this.requete = { ...this.requete, page: 0, sort: `${tri.active},${tri.direction || 'desc'}` };
    this.charge();
  }

  appliqueFiltres(filtres: Partial<LeadQuery>): void {
    // Retour a la premiere page : rester en page 4 apres un filtre affiche souvent du vide.
    this.requete = { ...this.requete, ...filtres, page: 0 };
    this.charge();
  }

  private charge(): void {
    this.enCours.set(true);
    this.api.liste(this.requete).subscribe({
      next: (page) => {
        this.lignes.set(page.content);
        this.total.set(page.totalElements);
        this.enCours.set(false);
      },
      error: () => this.enCours.set(false),
    });
  }
}
```

La barre de filtres : client (liste déroulante alimentée par `ClientApi`), statuts
(multi-sélection), score minimal, recherche libre, plage de dates. Chaque changement appelle
`appliqueFiltres`.

- [ ] **Step 6: Écrire l'écran de détail**

`/leads/:id` plutôt qu'un `mat-dialog` : une URL partageable vaut mieux qu'une modale, en
exploitation comme en démonstration. Trois blocs, dans l'ordre où on les lit quand on
diagnostique : le lead et son commercial, l'historique `crm_sync_attempt` du plus récent au
plus ancien, et l'événement brut avec **sa charge utile JSON**. C'est ce dernier bloc qui
répond à « pourquoi ce lead n'a pas de téléphone » sans ouvrir `psql`.

- [ ] **Step 7: Vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
puis `npm run build`
Expected: PASS.

```bash
git add frontend/src
git commit -m "feat: ecran des leads en pagination serveur et detail sur une route

Ni MatTableDataSource ni tri en memoire : appliques a une page deja paginee par
le serveur, ils pagineraient vingt-cinq lignes et afficheraient un total faux.
Les filtres absents ne partent pas — un « q » vide ferait un LIKE '%%' sur
toute la table. Le detail est une route et non une modale : une URL partageable
vaut mieux en exploitation comme en demonstration."
```

---

## Task 15: Dashboard et flux temps réel

**Files:**
- Create: `frontend/src/app/core/api/stats-api.ts`
- Create: `frontend/src/app/core/stream/lead-stream.ts`
- Modify: `frontend/src/app/features/dashboard/dashboard.ts` + `.html` + `.scss`
- Test: `frontend/src/app/core/stream/lead-stream.spec.ts`

**Interfaces:**
- Consumes: `GET /api/stats` (T5), `GET /api/stream/leads` (T11).
- Produces: l'écran d'accueil : compteurs, répartitions, flux des derniers événements.

**Pas d'`EventSource`** : il ne sait pas poser d'en-tête `Authorization`, et passer le jeton
en paramètre d'URL le ferait apparaître dans les journaux d'accès. Le service lit le flux par
`fetch` + `ReadableStream` et découpe les trames lui-même — une trentaine de lignes, et aucun
jeton en clair dans une URL.

Le découpage des trames est la seule logique frontend qui mérite un test unitaire isolé : une
trame SSE arrive en morceaux arbitraires, et un découpage naïf perd les événements coupés au
milieu.

- [ ] **Step 1: Écrire le test qui échoue**

```ts
import { decoupeTrames } from './lead-stream';

describe('decoupeTrames', () => {
  it('extrait un evenement complet et rend le reste', () => {
    const { evenements, reste } = decoupeTrames('event: lead\ndata: {"leadId":"a"}\n\n');

    expect(evenements).toEqual([{ nom: 'lead', donnees: '{"leadId":"a"}' }]);
    expect(reste).toBe('');
  });

  it('conserve une trame incomplete pour le morceau suivant', () => {
    // Un ReadableStream livre des morceaux arbitraires : une trame coupee en deux ne doit
    // ni etre perdue, ni etre lue a moitie.
    const premier = decoupeTrames('event: lead\ndata: {"leadI');
    expect(premier.evenements).toEqual([]);

    const second = decoupeTrames(premier.reste + 'd":"a"}\n\n');
    expect(second.evenements).toEqual([{ nom: 'lead', donnees: '{"leadId":"a"}' }]);
  });

  it('extrait deux evenements arrives ensemble', () => {
    const { evenements } = decoupeTrames(
      'event: lead\ndata: {"leadId":"a"}\n\nevent: dead-letter\ndata: {"id":"b"}\n\n',
    );

    expect(evenements.map((e) => e.nom)).toEqual(['lead', 'dead-letter']);
  });

  it('ignore les commentaires de maintien', () => {
    const { evenements } = decoupeTrames(': keep-alive\n\n');

    // Le commentaire existe pour empecher un proxy de couper la connexion, pas pour
    // produire une ligne a l'ecran.
    expect(evenements).toEqual([]);
  });
});
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `decoupeTrames` n'existe pas.

- [ ] **Step 3: Écrire `LeadStream`**

```ts
import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Auth } from '../auth/auth';
import { StreamEvent, DeadLetterView } from '../models/monitoring';

export interface TrameSse {
  nom: string;
  donnees: string;
}

/**
 * Decoupe un tampon SSE en trames completes et rend ce qui reste.
 *
 * Fonction pure et exportee : un ReadableStream livre des morceaux arbitraires, une trame
 * peut arriver coupee en deux, et c'est la seule logique du flux qui merite un test isole.
 */
export function decoupeTrames(tampon: string): { evenements: TrameSse[]; reste: string } {
  const evenements: TrameSse[] = [];
  const blocs = tampon.split('\n\n');
  // Le dernier bloc est incomplet tant qu'il n'est pas suivi d'une ligne vide.
  const reste = blocs.pop() ?? '';

  for (const bloc of blocs) {
    let nom = 'message';
    const lignesDeDonnees: string[] = [];
    for (const ligne of bloc.split('\n')) {
      if (ligne.startsWith(':')) {
        continue; // commentaire de maintien
      }
      if (ligne.startsWith('event:')) {
        nom = ligne.slice(6).trim();
      } else if (ligne.startsWith('data:')) {
        lignesDeDonnees.push(ligne.slice(5).trim());
      }
    }
    if (lignesDeDonnees.length > 0) {
      evenements.push({ nom, donnees: lignesDeDonnees.join('\n') });
    }
  }
  return { evenements, reste };
}

/**
 * Lecture du flux par fetch et non par EventSource : celui-ci ne sait pas poser d'en-tete
 * Authorization, et passer le jeton en parametre d'URL le ferait apparaitre dans tous les
 * journaux d'acces.
 *
 * La reconnexion est volontairement simple — un delai fixe puis nouvelle tentative : le
 * serveur expire ses emetteurs a trente minutes, donc une reconnexion est attendue, pas
 * exceptionnelle.
 */
@Injectable({ providedIn: 'root' })
export class LeadStream {
  private readonly auth = inject(Auth);
  private readonly zone = inject(NgZone);

  readonly derniersLeads = signal<StreamEvent[]>([]);
  readonly dernieresMorts = signal<DeadLetterView[]>([]);
  readonly connecte = signal(false);

  private controleur: AbortController | null = null;

  ouvre(): void {
    this.controleur?.abort();
    this.controleur = new AbortController();
    void this.lit(this.controleur.signal);
  }

  ferme(): void {
    this.controleur?.abort();
    this.controleur = null;
    this.connecte.set(false);
  }

  private async lit(signalDArret: AbortSignal): Promise<void> {
    try {
      const reponse = await fetch('/api/stream/leads', {
        headers: { Authorization: `Bearer ${this.auth.token()}` },
        signal: signalDArret,
      });
      if (!reponse.ok || !reponse.body) {
        throw new Error(`Flux refuse : ${reponse.status}`);
      }
      this.zone.run(() => this.connecte.set(true));

      const lecteur = reponse.body.getReader();
      const decodeur = new TextDecoder();
      let tampon = '';

      while (!signalDArret.aborted) {
        const { value, done } = await lecteur.read();
        if (done) {
          break;
        }
        tampon += decodeur.decode(value, { stream: true });
        const { evenements, reste } = decoupeTrames(tampon);
        tampon = reste;
        // fetch vit hors de la zone Angular : sans run(), les signaux changeraient sans
        // que la vue s'en apercoive.
        this.zone.run(() => evenements.forEach((trame) => this.applique(trame)));
      }
    } catch (erreur) {
      if (!signalDArret.aborted) {
        this.zone.run(() => this.connecte.set(false));
        setTimeout(() => this.ouvre(), 5000);
      }
    }
  }

  private applique(trame: TrameSse): void {
    const charge = JSON.parse(trame.donnees);
    if (trame.nom === 'lead') {
      // Fenetre glissante de cinquante : le flux anime l'ecran, il ne le remplace pas.
      this.derniersLeads.update((liste) => [charge, ...liste].slice(0, 50));
    } else if (trame.nom === 'dead-letter') {
      this.dernieresMorts.update((liste) => [charge, ...liste].slice(0, 50));
    }
  }
}
```

- [ ] **Step 4: Écrire `StatsApi` et l'écran**

`StatsApi.stats(clientId?, from?, to?)` — un `GET` avec les paramètres présents seulement,
même règle que `LeadApi`.

`dashboard.ts` : les compteurs par statut en cartes, le taux de conversion et la part
`GEMINI` / `RULES` en `mat-progress-bar`, le volume par commercial en barres, et la colonne
« flux » alimentée par `LeadStream`. `ouvre()` dans `ngOnInit`, `ferme()` dans `ngOnDestroy` —
un flux laissé ouvert après la navigation garderait une connexion et un émetteur serveur pour
un écran que personne ne regarde.

Pas de camembert ni de courbe : `mat-progress-bar` et des compteurs (3.13). Une valeur à zéro
s'affiche, elle ne se cache pas — c'est justement l'information.

- [ ] **Step 5: Vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
puis `npm run build`
Expected: PASS.

Vérification manuelle du critère de recette 4, une fois le backend lancé : envoyer un lead au
webhook et voir la ligne apparaître dans le flux **sans recharger**, puis passer `QUALIFIED`,
`ROUTED`, `SYNCED`.

```bash
git add frontend/src
git commit -m "feat: dashboard et flux temps reel lu par fetch

Pas d'EventSource : il ne sait pas poser d'en-tete Authorization, et passer le
jeton en parametre d'URL le mettrait dans tous les journaux d'acces. Le
decoupage des trames est une fonction pure et testee — un ReadableStream livre
des morceaux arbitraires, et une trame coupee en deux ne doit ni etre perdue ni
etre lue a moitie. Les signaux sont mis a jour dans la zone, fetch vivant
dehors."
```

---

## Task 16: Écrans File d'attente et Connecteurs

**Files:**
- Create: `frontend/src/app/core/api/queue-api.ts`
- Create: `frontend/src/app/core/api/dead-letter-api.ts`
- Create: `frontend/src/app/core/api/connector-api.ts`
- Modify: `frontend/src/app/features/queue/queue.ts` + `.html` + `.scss`
- Modify: `frontend/src/app/features/connectors/connectors.ts` + `.html` + `.scss`
- Test: `frontend/src/app/core/api/dead-letter-api.spec.ts`

**Interfaces:**
- Consumes: `GET /api/queues` (T10), `GET`/`POST /api/dead-letters` (T9),
  `GET /api/connectors` (T6).
- Produces: les deux derniers écrans. **Critères de recette 2 et 6.**

L'écran « File d'attente » porte les deux moitiés de la même histoire : la profondeur des
files en haut — dont celle de la DLQ, qui doit rester nulle — et le journal des morts en bas,
avec **le motif de chaque échec** et le bouton de rejeu.

**L'avertissement de réattribution s'affiche là où le serveur l'a mis** : `replayWarning` est
calculé par `DeadLetterQueryService` (T9), l'écran ne le devine pas. Rejouer un
`lead.qualified` refait passer le lead par l'attribution ; il peut changer de commercial et la
rotation se décale.

- [ ] **Step 1: Écrire le test qui échoue**

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeadLetterApi } from './dead-letter-api';

describe('DeadLetterApi', () => {
  let api: DeadLetterApi;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(DeadLetterApi);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('liste avec le filtre de statut', () => {
    api.liste({ page: 0, size: 25, status: 'PENDING' }).subscribe();

    const requete = httpMock.expectOne((r) => r.url === '/api/dead-letters');
    expect(requete.request.params.get('status')).toBe('PENDING');
    requete.flush({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('rejoue une ligne par un POST unitaire', () => {
    api.rejoue('abc').subscribe();

    // Pas d'endpoint de rejeu en masse : un rejeu de masse sur un incident non compris
    // multiplie l'incident. L'ecran boucle sur une selection explicite.
    const requete = httpMock.expectOne('/api/dead-letters/abc/replay');
    expect(requete.request.method).toBe('POST');
    requete.flush(null);
  });

  it('ecarte une ligne', () => {
    api.ecarte('abc').subscribe();

    const requete = httpMock.expectOne('/api/dead-letters/abc/discard');
    expect(requete.request.method).toBe('POST');
    requete.flush(null);
  });
});
```

- [ ] **Step 2: Vérifier que le test échoue**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `DeadLetterApi` n'existe pas.

- [ ] **Step 3: Écrire les trois services d'API**

`QueueApi.files()`, `ConnectorApi.connecteurs()`, et :

```ts
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { DeadLetterView, DeadLetterStatus } from '../models/monitoring';
import { PageResponse } from '../models/page-response';

@Injectable({ providedIn: 'root' })
export class DeadLetterApi {
  private readonly http = inject(HttpClient);

  liste(requete: {
    page: number;
    size: number;
    status?: DeadLetterStatus;
    originQueue?: string;
    clientId?: string;
  }) {
    let params = new HttpParams().set('page', requete.page).set('size', requete.size);
    for (const [cle, valeur] of Object.entries(requete)) {
      if (!['page', 'size'].includes(cle) && valeur !== undefined && valeur !== '') {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<PageResponse<DeadLetterView>>('/api/dead-letters', { params });
  }

  rejoue(id: string) {
    return this.http.post<void>(`/api/dead-letters/${id}/replay`, {});
  }

  ecarte(id: string) {
    return this.http.post<void>(`/api/dead-letters/${id}/discard`, {});
  }
}
```

- [ ] **Step 4: Écrire l'écran File d'attente**

Deux parties. En haut, une carte par file : nom, profondeur, nombre de consommateurs. **Un
zéro consommateur se signale visuellement** — c'est la mesure la plus lisible d'un listener
tombé. La profondeur de `leadflow.leads.dlq` se signale de même dès qu'elle est non nulle :
le journal devrait la vider.

En bas, la table du journal : date, file d'origine, client, **motif**, statut, actions. Les
règles d'interaction :

```ts
  rejoue(mort: DeadLetterView): void {
    // L'avertissement vient du serveur : la reattribution est une consequence d'une
    // decision de F4, pas une regle d'ecran.
    if (mort.replayWarning && !confirm(mort.replayWarning + '\n\nRejouer quand meme ?')) {
      return;
    }
    this.api.rejoue(mort.id).subscribe({
      next: () => this.charge(),
      error: (erreur) => this.signale(erreur),
    });
  }
```

`409` — la ligne a déjà été traitée, sans doute dans un autre onglet — se traduit par un
message et un rechargement de la liste, jamais par une erreur silencieuse. `503` dit que le
broker est injoignable et que la ligne **reste en attente** : c'est une information utile,
pas un échec de l'opérateur.

Une sélection multiple est possible, mais elle boucle sur l'appel unitaire, avec une
confirmation qui annonce le nombre de lignes. Il n'y a pas d'endpoint de masse, et ce n'est
pas un oubli.

- [ ] **Step 5: Écrire l'écran Connecteurs**

Une carte par fournisseur. Trois états visuellement distincts, ce qu'exige le critère de
recette 6 :

- **désactivé** — `enabled: false` : gris, mention « désactivé par configuration » ;
- **actif, sans trace** — compteurs à zéro : « aucune synchronisation à ce jour » ;
- **actif, dernière tentative en échec** : rouge, avec `lastFailureMessage` en clair et la
  date du dernier succès à côté.

Un fournisseur `implemented: false` mais configuré, ou l'inverse, est une anomalie de
déploiement : l'afficher explicitement plutôt que le taire.

Le détail par client se déplie sous la carte : succès, échecs, dernière tentative. C'est là
qu'on voit qu'un seul client casse pendant que les autres passent — donc que le problème est
dans son `crm_config`, pas dans l'adaptateur.

- [ ] **Step 6: Vérifier et committer**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
puis `npm run build`
Expected: PASS.

```bash
git add frontend/src
git commit -m "feat: ecrans file d'attente et connecteurs

L'avertissement de reattribution vient du serveur et non de l'ecran : c'est une
consequence de la non-idempotence du tour de role decidee en F4. Pas de bouton
« tout rejouer » — un rejeu de masse sur un incident non compris multiplie
l'incident ; une selection explicite boucle sur l'appel unitaire. L'ecran des
connecteurs distingue trois etats, dont « actif mais dernier appel en echec »,
que le critere de recette exige de ne pas confondre avec « desactive »."
```

---

## Task 17: Documentation et corrections d'invariants

**Files:**
- Create: `docs/monitoring-api.md`
- Modify: `CLAUDE.md`
- Modify: `docs/erp-integration-setup.md` (variables d'environnement du dashboard)
- Modify: `README.md` si présent

**Interfaces:**
- Consumes: tout ce que F6 a livré.
- Produces: la documentation du dépôt remise en accord avec le code.

`CLAUDE.md` porte **deux affirmations que F6 rend fausses**. Les laisser coûterait plus cher
que le code lui-même : la prochaine session les lirait comme des invariants.

- [ ] **Step 1: Écrire `docs/monitoring-api.md`**

Sur le modèle de `docs/webhook-integration.md`. Pour chaque endpoint : la méthode, le chemin,
les paramètres, un exemple `curl` complet avec le `Bearer`, et un exemple de réponse.

L'obtention du jeton en premier, parce que rien d'autre ne marche sans lui :

```bash
JETON=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"operateur","password":"..."}' | jq -r .token)

curl -s http://localhost:8080/api/leads?status=SYNCED -H "Authorization: Bearer $JETON" | jq
```

Une section sur les variables d'environnement, et **comment produire un hash BCrypt** — le
premier obstacle concret au déploiement :

```bash
# Le hash, jamais le mot de passe, va en configuration. Meme en dev.
htpasswd -bnBC 10 "" 'mot-de-passe' | tr -d ':\n'
```

Une section sur le journal des morts : ce qu'il contient, pourquoi il remplace la DLQ, et
**l'avertissement sur le rejeu de `lead.qualified`**.

- [ ] **Step 2: Corriger `CLAUDE.md`**

Trois modifications, dans l'ordre du fichier.

Section « Messaging » — remplacer :

> la DLQ est la source de vérité des leads en échec, et l'écran « File d'attente » du
> dashboard doit s'appuyer dessus

par une phrase qui dit que la DLQ est le **tuyau**, que la table `dead_letter` est la source
de vérité depuis F6, et que sa profondeur doit rester nulle. Mentionner le
`RepublishMessageRecoverer` et ce qu'il apporte.

Section « Base de données » — remplacer :

> Le schéma est désormais complet : F3 n'a rien eu à y ajouter, et les features suivantes ne
> devraient pas non plus.

par la mention de `V4__dead_letter.sql` et la raison en une phrase : une file de messages ne
sait pas être une liste paginée et filtrable.

Section « État actuel » — l'observabilité existe désormais. Dire ce qui reste vraiment
absent : les notifications au commercial (tâche d'agenda ERP, alerte des leads chauds), le
`seuilChaud` de F3 toujours inutilisé, la réattribution manuelle, le CRUD des clients, les
graphiques, et le déploiement (F7).

- [ ] **Step 3: Ajouter une section « Monitoring » à `CLAUDE.md`**

Après la section « Routage », sur le même modèle que les autres : les décisions qu'une
modification ne doit pas casser.

- **Le monitoring est un observateur.** Il lit les tables des autres étapes par ses propres
  repositories en lecture seule, n'écrit que `dead_letter`, et ne publie qu'un rejeu de
  message mort. `LeadQueryRepository` étend `Repository` nu, pas `JpaRepository` : aucune
  méthode d'écriture n'est même exposée.
- **Aucune entité JPA ne franchit la frontière HTTP.** `Client` porte `hmacSecret` et
  `crmConfig` **déchiffrés à la lecture** par les converters : sérialiser l'entité publierait
  le secret. Un test l'asserte sur le corps JSON, pas sur le DTO.
- **La file d'observation ne vole aucun message.** `leadflow.monitoring.events` est liée aux
  mêmes routing keys que les files métier ; un `DirectExchange` livre à toutes les files
  liées à une clé. Elle n'a pas de DLX — un échec d'affichage n'est pas un échec de lead.
- **Les filets de republication vivent dans `qualification/` et `routing/`**, pas dans
  `monitoring/` : ils publient sur le pipeline. Ils ignorent les leads portant une mort
  `PENDING`, sans quoi un échec permanent deviendrait une inondation.
- **Le dashboard est une console d'agence.** Un seul modèle d'utilisateur, aucun rôle, et le
  tenant est un filtre de requête (`?clientId=`) — jamais une donnée portée par le jeton.
- **Ajouter un endpoint de monitoring** = un `record` dans `monitoring/dto/`, une méthode de
  service `@Transactional(readOnly = true)`, un contrôleur. Jamais d'entité en sortie.

Mettre à jour aussi la section « Configuration » : `LEADFLOW_JWT_SECRET`,
`LEADFLOW_ADMIN_USER`, `LEADFLOW_ADMIN_PASSWORD_HASH`, et le fait que l'application refuse de
démarrer sans secret JWT — comme pour `LEADFLOW_MASTER_KEY`.

- [ ] **Step 4: Vérifier l'ensemble de F6**

```bash
cd backend && ./mvnw verify
cd ../frontend && npm test -- --watch=false --browsers=ChromeHeadless && npm run build
```

Puis la relecture des sept critères de recette de la spec, un par un, chacun contre une
vérification réelle et non contre un souvenir :

1. connexion, jeton, `401` sans jeton — `AuthenticationTest` ;
2. un lead en échec apparaît avec son motif et se rejoue — `CheminDEchecCompletTest`,
   `DeadLetterReplayServiceTest`, puis l'écran ;
3. compteurs cohérents avec la base — `StatsServiceTest`, puis un `select count(*)` de
   contrôle ;
4. un nouveau lead apparaît sans rechargement — vérification manuelle (T15) ;
5. aucune réponse ne contient de secret — `ClientDirectoryTest` ;
6. désactivé ≠ actif en échec — `ConnectorHealthServiceTest`, puis l'écran ;
7. le filet ne republie pas en boucle une mort `PENDING` — `QualifiedLeadRelayTest`.

- [ ] **Step 5: Commit final et fusion**

```bash
git add docs/monitoring-api.md CLAUDE.md docs/erp-integration-setup.md
git commit -m "docs: API de monitoring, et invariants de CLAUDE.md remis d'accord avec le code

Deux affirmations devenues fausses sont corrigees plutot que laissees : la DLQ
n'est plus la source de verite des leads en echec — la table dead_letter l'est,
la file n'etant que le tuyau — et le schema n'est plus complet depuis V3, une
file de messages ne sachant pas etre une liste paginee et filtrable. Une
section Monitoring documente les regles que F6 introduit."
```

Puis la fusion, selon la méthode du projet : la branche `feature/f6-monitoring-dashboard` est
fusionnée dans `main` et **conservée** — elle sert d'historique de la feature.

```bash
git checkout main
git merge --no-ff feature/f6-monitoring-dashboard
```

---

## Après F6

Le pipeline est observable et pilotable. Reste **F7** : durcissement, intégration continue,
déploiement, élargissement de CORS, et le mémoire de soutenance. Les éléments hors périmètre
de F6 — réattribution manuelle, alerte des leads chauds et tâche d'agenda dans l'ERP,
graphiques, rôles et espace par client — y sont candidats, mais aucun n'est un prérequis du
déploiement.

# F2 — Capture sécurisée : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ouvrir l'entrée du pipeline : un webhook signé qui authentifie l'appelant, écrit l'événement brut et le met en route vers RabbitMQ sans jamais perdre un lead accepté.

**Architecture:** Le contrôleur lit le corps en `String` brut et ne décide rien ; `LeadCaptureService` authentifie puis écrit en une transaction ; la publication part après le commit via `@TransactionalEventListener(AFTER_COMMIT)`, et un balayage périodique reprend ce qui n'est pas parti. L'idempotence est portée par un index unique en base, pas par du code.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA / Hibernate 7, Jackson 3 (`tools.jackson`), Spring AMQP, PostgreSQL 16, Flyway, Lombok, JUnit 5, AssertJ, MockMvc, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-22-f2-capture-securisee-design.md`

## Global Constraints

- Branche de travail : `feature/f2-capture-securisee`. Ne jamais commiter sur `main`.
- Toutes les commandes Maven s'exécutent depuis `backend/`. Sous Git Bash : `./mvnw`. Sous PowerShell : `.\mvnw.cmd`.
- **Le daemon Docker doit tourner** : tous les tests de persistance et de messagerie démarrent des conteneurs Testcontainers.
- `ddl-auto: validate` : Hibernate ne crée jamais de table. Toute évolution de schéma passe par un nouveau fichier `src/main/resources/db/migration/V<n>__description.sql`.
- **Aucun secret dans les logs ni dans une réponse HTTP** : ni `client.hmac_secret`, ni la signature attendue. Le seul élément journalisable d'une requête refusée est la clé publique tentée.
- Les noms de files et d'exchanges viennent des constantes de `config/RabbitMQConfig.java` — ne jamais les écrire en dur.
- Les nouveaux réglages vont sous `leadflow.webhook.*` et se lisent via le `record` `@ConfigurationProperties` existant `config/WebhookProperties.java`.
- Code et commentaires en français **sans accents** dans le code Java (convention du dépôt) ; la documentation Markdown garde les accents.
- Messages de commit en français sans accents, préfixés `feat:`, `test:`, `fix:` ou `docs:`.

---

## Structure des fichiers

```
backend/src/main/java/com/leadflow/
├── capture/
│   ├── LeadWebhookController.java     (créé, T3)  endpoint, lit le corps brut
│   ├── LeadCaptureService.java        (créé, T3)  @Transactional : client, signature, insertion
│   ├── HmacSignatureVerifier.java     (créé, T2)  pur : parse, fenetre, HMAC, temps constant
│   ├── CaptureAccepted.java           (créé, T3)  corps de la reponse 202 (record)
│   ├── LeadCapturedEvent.java         (créé, T5)  evenement applicatif interne (record)
│   ├── LeadEventPublisher.java        (créé, T5)  AFTER_COMMIT -> RabbitMQ -> statut
│   ├── CapturedLeadMessage.java       (créé, T5)  contrat de file consomme par F3 (record)
│   ├── PendingEventRelay.java         (créé, T6)  @Scheduled : filet de republication
│   └── RawLeadEventRepository.java    (modifié, T1) + deux derives
├── common/
│   ├── WebhookAuthenticationException.java (créé, T3) une exception pour les cinq causes
│   ├── PayloadRejectedException.java       (créé, T3) corps illisible ou trop gros
│   └── ApiExceptionHandler.java            (créé, T3) @RestControllerAdvice -> ProblemDetail
└── config/
    ├── WebhookProperties.java         (modifié, T2 puis T6) + trois reglages
    └── SchedulingConfig.java          (créé, T6)  @EnableScheduling

backend/src/main/resources/
├── db/migration/V3__raw_lead_event_idempotence.sql (créé, T1)
└── application.yml                                  (modifié, T2 puis T6)

backend/src/test/java/com/leadflow/capture/
├── HmacSignatureVerifierTest.java     (créé, T2)  unitaire, sans Spring
├── LeadCaptureIntegrationTest.java    (créé, T3, étendu T4 et T5)
├── RawLeadEventIdempotenceTest.java   (créé, T1)  l'index unique refuse le doublon
└── PendingEventRelayTest.java         (créé, T6)

docs/
└── webhook-integration.md             (créé, T7)
```

**Frontière publique de la couche :** `CapturedLeadMessage`. F3 ne connaîtra rien d'autre de `capture/`.

---

## Task 1: Migration V3 et dérivés du repository

L'idempotence de la spec §3.4 est portée par la base, pas par le code : c'est ce qui la rend vraie même si deux requêtes arrivent en parallèle. Cette tâche pose l'index et les deux requêtes dont les tâches suivantes auront besoin.

**Files:**
- Create : `backend/src/main/resources/db/migration/V3__raw_lead_event_idempotence.sql`
- Create : `backend/src/test/java/com/leadflow/capture/RawLeadEventIdempotenceTest.java`
- Modify : `backend/src/main/java/com/leadflow/capture/RawLeadEventRepository.java`

**Interfaces:**
- Consumes : `RawLeadEvent`, `RawLeadEventStatus`, `RawLeadEventRepository` (F1).
- Produces : `Optional<RawLeadEvent> findByClientIdAndSignature(UUID clientId, String signature)` et `List<RawLeadEvent> findByStatusInAndReceivedAtBefore(Collection<RawLeadEventStatus> statuts, Instant limite)`.

- [ ] **Step 1: Écrire le test d'idempotence**

Créer `backend/src/test/java/com/leadflow/capture/RawLeadEventIdempotenceTest.java` :

```java
package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * L'idempotence du webhook est portee par un index unique et non par une verification
 * applicative : deux requetes concurrentes passeraient toutes les deux un simple
 * « existe-t-il deja ? ».
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RawLeadEventIdempotenceTest {

    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;

    private UUID clientEnregistre() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        return clientRepository.saveAndFlush(client).getId();
    }

    private RawLeadEvent evenement(UUID clientId, String signature) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature(signature);
        evenement.setReceivedAt(Instant.now());
        return evenement;
    }

    @Test
    void refuseDeuxEvenementsDeMemeSignaturePourUnMemeClient() {
        UUID clientId = clientEnregistre();
        rawLeadEventRepository.saveAndFlush(evenement(clientId, "t=1755820000,v1=abcdef"));

        assertThatThrownBy(() -> rawLeadEventRepository
                        .saveAndFlush(evenement(clientId, "t=1755820000,v1=abcdef")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accepteLaMemeSignaturePourDeuxClientsDifferents() {
        // Deux clients ont des secrets differents : une collision de signature entre eux
        // n'a aucune signification, l'unicite ne doit pas etre globale.
        UUID premier = clientEnregistre();
        UUID second = clientEnregistre();

        rawLeadEventRepository.saveAndFlush(evenement(premier, "t=1755820000,v1=abcdef"));
        RawLeadEvent chezLautre =
                rawLeadEventRepository.saveAndFlush(evenement(second, "t=1755820000,v1=abcdef"));

        assertThat(chezLautre.getId()).isNotNull();
    }

    @Test
    void retrouveUnEvenementParClientEtSignature() {
        UUID clientId = clientEnregistre();
        RawLeadEvent enregistre =
                rawLeadEventRepository.saveAndFlush(evenement(clientId, "t=1755820001,v1=123456"));

        assertThat(rawLeadEventRepository.findByClientIdAndSignature(clientId, "t=1755820001,v1=123456"))
                .get()
                .extracting(RawLeadEvent::getId)
                .isEqualTo(enregistre.getId());
    }

    @Test
    void listeLesEvenementsNonPubliesPlusVieuxQueLaLimite() {
        UUID clientId = clientEnregistre();
        RawLeadEvent ancien = evenement(clientId, "t=1755820002,v1=ancien");
        ancien.setReceivedAt(Instant.now().minusSeconds(600));
        rawLeadEventRepository.saveAndFlush(ancien);

        RawLeadEvent recent = evenement(clientId, "t=1755820003,v1=recent");
        rawLeadEventRepository.saveAndFlush(recent);

        RawLeadEvent publie = evenement(clientId, "t=1755820004,v1=publie");
        publie.setReceivedAt(Instant.now().minusSeconds(600));
        publie.setStatus(RawLeadEventStatus.PUBLISHED);
        rawLeadEventRepository.saveAndFlush(publie);

        assertThat(rawLeadEventRepository.findByStatusInAndReceivedAtBefore(
                        java.util.List.of(RawLeadEventStatus.RECEIVED, RawLeadEventStatus.FAILED),
                        Instant.now().minusSeconds(60)))
                .extracting(RawLeadEvent::getId)
                .containsExactly(ancien.getId());
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=RawLeadEventIdempotenceTest
```

Attendu : échec de compilation — `findByClientIdAndSignature` et `findByStatusInAndReceivedAtBefore` n'existent pas.

- [ ] **Step 3: Écrire la migration**

Créer `backend/src/main/resources/db/migration/V3__raw_lead_event_idempotence.sql` :

```sql
-- Idempotence du webhook (F2). La fenetre de tolerance sur l'horodatage borne le rejeu
-- mais ne l'empeche pas : dans les cinq minutes, une requete captee peut etre renvoyee
-- telle quelle. L'unicite est posee en base et non verifiee en Java, sinon deux requetes
-- concurrentes passeraient toutes les deux le controle applicatif.
--
-- La colonne signature porte la valeur d'en-tete complete (t=...,v1=...) : c'est ce
-- couple, et non le seul hexadecimal, qui identifie un rejeu exact.
CREATE UNIQUE INDEX uk_raw_lead_event_client_signature
    ON raw_lead_event (client_id, signature);

-- Le filet de republication cherche les lignes non publiees ANTERIEURES a un instant.
-- L'index simple sur status de V1 ne sait pas borner par age ; celui-ci le remplace.
DROP INDEX idx_raw_lead_event_status;

CREATE INDEX idx_raw_lead_event_status_received
    ON raw_lead_event (status, received_at);
```

- [ ] **Step 4: Ajouter les deux dérivés au repository**

Remplacer le contenu de `backend/src/main/java/com/leadflow/capture/RawLeadEventRepository.java` :

```java
package com.leadflow.capture;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawLeadEventRepository extends JpaRepository<RawLeadEvent, UUID> {

    /** Evenements en echec d'un client, pour l'ecran de rejeu du dashboard en F6. */
    List<RawLeadEvent> findByClientIdAndStatusOrderByReceivedAtDesc(
            UUID clientId, RawLeadEventStatus status);

    /**
     * Rejeu exact : meme client, meme en-tete de signature. Adosse a l'index unique
     * {@code uk_raw_lead_event_client_signature}.
     */
    Optional<RawLeadEvent> findByClientIdAndSignature(UUID clientId, String signature);

    /** Ce que le filet de republication doit reprendre : non publie et assez vieux. */
    List<RawLeadEvent> findByStatusInAndReceivedAtBefore(
            Collection<RawLeadEventStatus> statuts, Instant limite);
}
```

- [ ] **Step 5: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=RawLeadEventIdempotenceTest
```

Attendu : 4 tests verts. Si Flyway échoue sur un checksum, c'est que la base de développement contient un V3 différent : `docker compose down -v` en dev. Les tests, eux, partent d'un conteneur neuf.

- [ ] **Step 6: Lancer la suite complète et commiter**

```bash
./mvnw test
```

Attendu : toute la suite verte, 4 tests de plus qu'avant.

```bash
git add backend/src/main/resources/db/migration/V3__raw_lead_event_idempotence.sql \
        backend/src/main/java/com/leadflow/capture/RawLeadEventRepository.java \
        backend/src/test/java/com/leadflow/capture/RawLeadEventIdempotenceTest.java
git commit -m "feat: index unique client_id + signature pour l'idempotence du webhook"
```

---

## Task 2: Vérificateur de signature HMAC

Le cœur sécurité de la feature, et la seule classe entièrement testable sans conteneur. Elle ne connaît ni la base, ni HTTP : elle reçoit un secret, un corps, un en-tête et un instant.

**Files:**
- Create : `backend/src/main/java/com/leadflow/capture/HmacSignatureVerifier.java`
- Create : `backend/src/main/java/com/leadflow/common/WebhookAuthenticationException.java`
- Create : `backend/src/test/java/com/leadflow/capture/HmacSignatureVerifierTest.java`
- Modify : `backend/src/main/java/com/leadflow/config/WebhookProperties.java`
- Modify : `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes : `WebhookProperties`.
- Produces : `HmacSignatureVerifier.verifie(String secret, String corpsBrut, String enTeteSignature, Instant maintenant)` — rend `void`, lève `WebhookAuthenticationException`. Et `WebhookAuthenticationException(String raisonInterne)`.

- [ ] **Step 1: Écrire le test du vérificateur**

Créer `backend/src/test/java/com/leadflow/capture/HmacSignatureVerifierTest.java` :

```java
package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.common.WebhookAuthenticationException;
import com.leadflow.config.WebhookProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Aucun conteneur, aucun contexte Spring : l'horloge est un parametre, donc aucun test ne
 * dort et la fenetre de tolerance se verifie a la seconde pres.
 */
class HmacSignatureVerifierTest {

    private static final String SECRET = "secret-de-signature";
    private static final String CORPS = "{\"source\":\"formulaire-devis\"}";
    private static final Instant MAINTENANT = Instant.ofEpochSecond(1755820000L);

    private final HmacSignatureVerifier verificateur =
            new HmacSignatureVerifier(new WebhookProperties(
                    "X-Leadflow-Signature", Duration.ofMinutes(5), 65536, null, null));

    private static String signe(String secret, long horodatage, String corps) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            String hex = HexFormat.of()
                    .formatHex(mac.doFinal((horodatage + "." + corps).getBytes(UTF_8)));
            return "t=" + horodatage + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accepteUneSignatureValideDansLaFenetre() {
        String enTete = signe(SECRET, MAINTENANT.getEpochSecond(), CORPS);

        assertThatCode(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .doesNotThrowAnyException();
    }

    @Test
    void refuseUnEnTeteAbsent() {
        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, null, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUnEnTeteMalforme() {
        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, "pas-un-en-tete", MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUneSignatureFausse() {
        String enTete = signe("mauvais-secret", MAINTENANT.getEpochSecond(), CORPS);

        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUnCorpsModifieApresSignature() {
        String enTete = signe(SECRET, MAINTENANT.getEpochSecond(), CORPS);

        assertThatThrownBy(() -> verificateur.verifie(
                        SECRET, "{\"source\":\"autre-chose\"}", enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void rejectsExpiredTimestamp() {
        // Nom en anglais : c'est celui que CLAUDE.md cite en exemple de commande.
        long vieux = MAINTENANT.minus(Duration.ofMinutes(6)).getEpochSecond();
        String enTete = signe(SECRET, vieux, CORPS);

        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void refuseUnHorodatageTropLoinDansLeFutur() {
        // Une horloge client en avance ne doit pas non plus ouvrir la fenetre indefiniment.
        long futur = MAINTENANT.plus(Duration.ofMinutes(6)).getEpochSecond();
        String enTete = signe(SECRET, futur, CORPS);

        assertThatThrownBy(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .isInstanceOf(WebhookAuthenticationException.class);
    }

    @Test
    void accepteUnHorodatageAuBordDeLaFenetre() {
        long bord = MAINTENANT.minus(Duration.ofMinutes(5)).getEpochSecond();
        String enTete = signe(SECRET, bord, CORPS);

        assertThatCode(() -> verificateur.verifie(SECRET, CORPS, enTete, MAINTENANT))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=HmacSignatureVerifierTest
```

Attendu : échec de compilation — `HmacSignatureVerifier` et `WebhookAuthenticationException` n'existent pas, et `WebhookProperties` n'a que deux composants.

- [ ] **Step 3: Écrire l'exception d'authentification**

Créer `backend/src/main/java/com/leadflow/common/WebhookAuthenticationException.java` :

```java
package com.leadflow.common;

/**
 * Une seule exception pour les cinq causes de refus : cle publique inconnue, client
 * desactive, en-tete absent, signature fausse, horodatage hors fenetre.
 *
 * <p>C'est deliberement une seule classe : distinguer les causes cote HTTP offrirait a qui
 * sonde l'API un oracle sur les cles publiques existantes et sur l'etat des clients. Le
 * message porte la raison interne, destinee aux logs serveur, jamais a la reponse.
 */
public class WebhookAuthenticationException extends RuntimeException {

    public WebhookAuthenticationException(String raisonInterne) {
        super(raisonInterne);
    }
}
```

- [ ] **Step 4: Élargir les propriétés**

Remplacer le contenu de `backend/src/main/java/com/leadflow/config/WebhookProperties.java` :

```java
package com.leadflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres de verification des webhooks entrants. Le secret de signature n'est pas ici :
 * chaque client a le sien, porte par {@code client.hmac_secret}, chiffre au repos.
 *
 * @param signatureHeader nom de l'en-tete portant {@code t=...,v1=...}
 * @param tolerance ecart maximal accepte entre l'horodatage signe et l'heure du serveur,
 *     dans les deux sens : une horloge client en avance ne doit pas ouvrir la fenetre
 * @param maxPayloadBytes taille de corps au-dela de laquelle la requete est refusee
 * @param relayAfter age minimal d'une ligne non publiee avant que le filet la reprenne
 * @param relayInterval periode de balayage du filet
 */
@ConfigurationProperties(prefix = "leadflow.webhook")
public record WebhookProperties(
        String signatureHeader,
        Duration tolerance,
        int maxPayloadBytes,
        Duration relayAfter,
        Duration relayInterval) {
}
```

Puis, dans `backend/src/main/resources/application.yml`, remplacer le bloc `webhook` :

```yaml
  webhook:
    signature-header: X-Leadflow-Signature
    # Fenetre de tolerance anti-rejeu sur l'horodatage de la requete, dans les deux sens.
    tolerance: 5m
    # Applique apres lecture du corps : protege la base et la file, pas la memoire du
    # serveur. Un vrai garde-fou memoire releve du reverse-proxy (F7).
    max-payload-bytes: 65536
    # Filet de republication : age minimal d'une ligne non publiee, et periode de balayage.
    relay-after: 2m
    relay-interval: 30s
```

- [ ] **Step 5: Écrire le vérificateur**

Créer `backend/src/main/java/com/leadflow/capture/HmacSignatureVerifier.java` :

```java
package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.leadflow.common.WebhookAuthenticationException;
import com.leadflow.config.WebhookProperties;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifie l'en-tete {@code X-Leadflow-Signature: t=<epoch>,v1=<hex>}, ou le hexadecimal est
 * {@code HMAC-SHA256(secret du client, t + "." + corps brut)}.
 *
 * <p>L'horodatage fait partie de la charge signee : il ne peut donc pas etre bricole sans
 * invalider la signature. C'est la raison du format a un seul en-tete plutot que deux.
 *
 * <p>Ne connait ni la base, ni HTTP, ni l'horloge du systeme : tout arrive en parametre.
 * C'est ce qui rend la fenetre de tolerance testable a la seconde pres, sans conteneur et
 * sans faire dormir un test.
 */
@Component
public class HmacSignatureVerifier {

    private static final String ALGORITHME = "HmacSHA256";

    private final WebhookProperties properties;

    public HmacSignatureVerifier(WebhookProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws WebhookAuthenticationException si l'en-tete est absent, malforme, hors
     *     fenetre, ou si la signature ne correspond pas. Le message est destine aux logs.
     */
    public void verifie(String secret, String corpsBrut, String enTeteSignature, Instant maintenant) {
        if (enTeteSignature == null || enTeteSignature.isBlank()) {
            throw new WebhookAuthenticationException("En-tete de signature absent");
        }

        long horodatage = horodatage(enTeteSignature);
        String signatureFournie = valeur(enTeteSignature, "v1");

        Duration ecart = Duration.between(Instant.ofEpochSecond(horodatage), maintenant).abs();
        if (ecart.compareTo(properties.tolerance()) > 0) {
            throw new WebhookAuthenticationException(
                    "Horodatage hors fenetre : ecart de " + ecart.toSeconds() + "s");
        }

        String attendue = calcule(secret, horodatage + "." + corpsBrut);
        // Comparaison en temps constant : une comparaison de chaines ordinaire s'arrete au
        // premier octet different et laisse deduire la signature attendue octet par octet.
        if (!MessageDigest.isEqual(attendue.getBytes(UTF_8), signatureFournie.getBytes(UTF_8))) {
            throw new WebhookAuthenticationException("Signature invalide");
        }
    }

    private long horodatage(String enTete) {
        try {
            return Long.parseLong(valeur(enTete, "t"));
        } catch (NumberFormatException e) {
            throw new WebhookAuthenticationException("Horodatage illisible dans l'en-tete");
        }
    }

    /** Extrait {@code cle=valeur} d'un en-tete de la forme {@code t=...,v1=...}. */
    private String valeur(String enTete, String cle) {
        for (String partie : enTete.split(",")) {
            String[] paire = partie.trim().split("=", 2);
            if (paire.length == 2 && paire[0].trim().equals(cle)) {
                return paire[1].trim();
            }
        }
        throw new WebhookAuthenticationException("En-tete de signature malforme : '" + cle + "' absent");
    }

    private String calcule(String secret, String charge) {
        try {
            Mac mac = Mac.getInstance(ALGORITHME);
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), ALGORITHME));
            return HexFormat.of().formatHex(mac.doFinal(charge.getBytes(UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            // Algorithme absent de la JVM ou secret vide : ce n'est pas un refus
            // d'authentification mais une defaillance de configuration.
            throw new IllegalStateException("Calcul HMAC impossible", e);
        }
    }
}
```

- [ ] **Step 6: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=HmacSignatureVerifierTest
```

Attendu : 8 tests verts.

- [ ] **Step 7: Lancer la suite complète et commiter**

```bash
./mvnw test
```

Attendu : toute la suite verte. Si `BackendApplicationTests` échoue au démarrage sur `WebhookProperties`, c'est que `application.yml` n'a pas reçu les trois nouveaux réglages.

```bash
git add backend/src/main/java/com/leadflow/capture/HmacSignatureVerifier.java \
        backend/src/main/java/com/leadflow/common/WebhookAuthenticationException.java \
        backend/src/main/java/com/leadflow/config/WebhookProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/leadflow/capture/HmacSignatureVerifierTest.java
git commit -m "feat: verificateur de signature HMAC avec fenetre anti-rejeu"
```

---

## Task 3: Endpoint, service de capture et réponses d'erreur

Le chemin synchrone complet, sans publication : authentifier, parser, écrire, répondre `202`. Le contrôleur ne décide rien ; les cinq causes de refus rendent la même réponse.

**Files:**
- Create : `backend/src/main/java/com/leadflow/capture/LeadWebhookController.java`
- Create : `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java`
- Create : `backend/src/main/java/com/leadflow/capture/CaptureAccepted.java`
- Create : `backend/src/main/java/com/leadflow/common/PayloadRejectedException.java`
- Create : `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java`
- Create : `backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java`

**Interfaces:**
- Consumes : `HmacSignatureVerifier.verifie(...)`, `WebhookAuthenticationException`, `ClientRepository.findByPublicKeyAndActiveTrue(String)`, `RawLeadEventRepository`.
- Produces : `LeadCaptureService.capture(String clientKey, String corpsBrut, String enTeteSignature)` rendant `CaptureAccepted(UUID eventId)` ; `PayloadRejectedException(String message, HttpStatus statut)`.

- [ ] **Step 1: Écrire le test d'intégration**

Créer `backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java` :

```java
package com.leadflow.capture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadCaptureIntegrationTest {

    private static final String SECRET = "secret-de-signature";
    private static final String CORPS =
            "{\"source\":\"formulaire-devis\",\"email\":\"karim@acme.test\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;

    private String clePublique;
    private UUID clientId;

    @BeforeEach
    void preparerUnClient() {
        clePublique = "cle-" + UUID.randomUUID();
        Client client = new Client();
        client.setPublicKey(clePublique);
        client.setName("Boutique de test");
        client.setHmacSecret(SECRET);
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        clientId = clientRepository.saveAndFlush(client).getId();
    }

    static String signe(String secret, long horodatage, String corps) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            String hex = HexFormat.of()
                    .formatHex(mac.doFinal((horodatage + "." + corps).getBytes(UTF_8)));
            return "t=" + horodatage + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String enTeteValide(String secret, String corps) {
        return signe(secret, Instant.now().getEpochSecond(), corps);
    }

    @Test
    void accepteUneRequeteSigneeEtEcritLEvenementBrut() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        List<RawLeadEvent> evenements = rawLeadEventRepository.findAll();
        assertThat(evenements).hasSize(1);
        RawLeadEvent evenement = evenements.getFirst();
        assertThat(evenement.getClientId()).isEqualTo(clientId);
        assertThat(evenement.getSource()).isEqualTo("formulaire-devis");
        assertThat(evenement.getPayload()).containsEntry("email", "karim@acme.test");
        assertThat(evenement.getSignature()).startsWith("t=");
    }

    @Test
    void refuseUneClePubliqueInconnue() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", "cle-qui-nexiste-pas")
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());

        assertThat(rawLeadEventRepository.findAll()).isEmpty();
    }

    @Test
    void refuseUnClientDesactive() throws Exception {
        Client client = clientRepository.findByPublicKeyAndActiveTrue(clePublique).orElseThrow();
        client.setActive(false);
        clientRepository.saveAndFlush(client);

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());

        assertThat(rawLeadEventRepository.findAll()).isEmpty();
    }

    @Test
    void refuseUneSignatureAbsente() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refuseUneSignatureFausse() throws Exception {
        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide("mauvais-secret", CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refuseUnHorodatagePerime() throws Exception {
        long vieux = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", signe(SECRET, vieux, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repondLaMemeChoseQuelleQueSoitLaCauseDuRefus() throws Exception {
        // L'uniformite est le point : un attaquant ne doit pas pouvoir distinguer une cle
        // publique inexistante d'une signature fausse.
        String corpsInconnue = mockMvc.perform(post("/api/webhooks/leads/{cle}", "cle-inexistante")
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn().getResponse().getContentAsString();

        String corpsSignatureFausse = mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide("mauvais-secret", CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andReturn().getResponse().getContentAsString();

        assertThat(corpsInconnue).isEqualTo(corpsSignatureFausse);
    }

    @Test
    void neDeserialisePasLeCorpsAvantDAvoirAuthentifie() throws Exception {
        // JSON invalide ET signature invalide : la reponse doit etre 401 et non 400. C'est
        // la preuve que rien n'est parse avant que l'appelant soit authentifie.
        String corpsInvalide = "{ceci n'est pas du json";

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide("mauvais-secret", corpsInvalide))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsInvalide))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refuseUnCorpsIllisibleUneFoisAuthentifie() throws Exception {
        String corpsInvalide = "{ceci n'est pas du json";

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, corpsInvalide))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsInvalide))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUnCorpsSansSource() throws Exception {
        String sansSource = "{\"email\":\"karim@acme.test\"}";

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, sansSource))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sansSource))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refuseUnCorpsTropGros() throws Exception {
        String enorme = "{\"source\":\"formulaire\",\"message\":\"" + "a".repeat(70000) + "\"}";

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, enorme))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enorme))
                .andExpect(status().isPayloadTooLarge());
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=LeadCaptureIntegrationTest
```

Attendu : échec — l'endpoint n'existe pas, les requêtes répondent `404` ou `401` selon la chaîne de filtres.

- [ ] **Step 3: Écrire les deux exceptions et le gestionnaire REST**

Créer `backend/src/main/java/com/leadflow/common/PayloadRejectedException.java` :

```java
package com.leadflow.common;

import org.springframework.http.HttpStatus;

/**
 * Corps refuse APRES authentification : illisible, sans {@code source}, ou trop gros. La
 * distinction avec {@link WebhookAuthenticationException} est essentielle — ici l'appelant
 * est deja authentifie, on peut donc lui dire ce qui ne va pas sans rien divulguer.
 */
public class PayloadRejectedException extends RuntimeException {

    private final HttpStatus statut;

    public PayloadRejectedException(String message, HttpStatus statut) {
        super(message);
        this.statut = statut;
    }

    public HttpStatus statut() {
        return statut;
    }
}
```

Créer `backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java` :

```java
package com.leadflow.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduction des exceptions applicatives en reponses HTTP. Premiere pierre de la gestion
 * d'erreurs REST du projet : F2 est le premier endpoint.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Reponse deliberement avare et identique pour les cinq causes de refus. La raison
     * exacte n'existe que dans ce log : la distinguer cote client donnerait un oracle sur
     * les cles publiques existantes et sur l'etat d'activation des clients.
     */
    @ExceptionHandler(WebhookAuthenticationException.class)
    ProblemDetail refusDAuthentification(WebhookAuthenticationException echec) {
        log.warn("Webhook refuse : {}", echec.getMessage());
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Signature invalide ou expiree");
    }

    @ExceptionHandler(PayloadRejectedException.class)
    ProblemDetail corpsRefuse(PayloadRejectedException echec) {
        return ProblemDetail.forStatusAndDetail(echec.statut(), echec.getMessage());
    }
}
```

- [ ] **Step 4: Écrire le corps de réponse et le service**

Créer `backend/src/main/java/com/leadflow/capture/CaptureAccepted.java` :

```java
package com.leadflow.capture;

import java.util.UUID;

/**
 * Corps du {@code 202}. L'identifiant est rendu pour qu'un integrateur puisse correler sa
 * soumission avec ce qu'il verra plus tard dans le dashboard.
 */
public record CaptureAccepted(UUID eventId) {
}
```

Créer `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java` :

```java
package com.leadflow.capture;

import com.leadflow.common.PayloadRejectedException;
import com.leadflow.common.WebhookAuthenticationException;
import com.leadflow.config.WebhookProperties;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Le travail synchrone du webhook, et rien de plus : authentifier, ecrire, rendre la main.
 * Aucune validation metier du contenu — ni email, ni telephone, ni doublon fonctionnel :
 * tout cela appartient a la qualification (F3), derriere la file.
 *
 * <p>L'ordre des operations n'est pas negociable : le corps n'est deserialise qu'une fois
 * l'appelant authentifie. Faire tourner Jackson sur une entree non authentifiee reviendrait
 * a executer du code sur une donnee dont on n'a pas encore verifie l'origine.
 */
@Service
public class LeadCaptureService {

    private final ClientRepository clientRepository;
    private final RawLeadEventRepository rawLeadEventRepository;
    private final HmacSignatureVerifier verificateur;
    private final ObjectMapper objectMapper;
    private final WebhookProperties properties;

    public LeadCaptureService(
            ClientRepository clientRepository,
            RawLeadEventRepository rawLeadEventRepository,
            HmacSignatureVerifier verificateur,
            ObjectMapper objectMapper,
            WebhookProperties properties) {
        this.clientRepository = clientRepository;
        this.rawLeadEventRepository = rawLeadEventRepository;
        this.verificateur = verificateur;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public CaptureAccepted capture(String clientKey, String corpsBrut, String enTeteSignature) {
        // Un client desactive est simplement introuvable : le repository filtre sur
        // active=true, ce qui rend les deux cas indistinguables sans effort particulier.
        Client client = clientRepository.findByPublicKeyAndActiveTrue(clientKey)
                .orElseThrow(() -> new WebhookAuthenticationException(
                        "Cle publique inconnue ou client desactive : " + clientKey));

        verificateur.verifie(client.getHmacSecret(), corpsBrut, enTeteSignature, Instant.now());

        int taille = corpsBrut.getBytes(StandardCharsets.UTF_8).length;
        if (taille > properties.maxPayloadBytes()) {
            throw new PayloadRejectedException(
                    "Corps de " + taille + " octets, maximum " + properties.maxPayloadBytes(),
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }

        Map<String, Object> payload = deserialise(corpsBrut);
        Object source = payload.get("source");
        if (!(source instanceof String canal) || canal.isBlank()) {
            throw new PayloadRejectedException(
                    "Le champ 'source' est obligatoire", HttpStatus.BAD_REQUEST);
        }

        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(client.getId());
        evenement.setSource(canal);
        evenement.setPayload(payload);
        evenement.setSignature(enTeteSignature);
        evenement.setReceivedAt(Instant.now());
        evenement.setStatus(RawLeadEventStatus.RECEIVED);

        RawLeadEvent enregistre = rawLeadEventRepository.saveAndFlush(evenement);
        return new CaptureAccepted(enregistre.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserialise(String corpsBrut) {
        try {
            return objectMapper.readValue(corpsBrut, Map.class);
        } catch (JacksonException e) {
            throw new PayloadRejectedException("Corps JSON illisible", HttpStatus.BAD_REQUEST);
        }
    }
}
```

- [ ] **Step 5: Écrire le contrôleur**

Créer `backend/src/main/java/com/leadflow/capture/LeadWebhookController.java` :

```java
package com.leadflow.capture;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entree du pipeline. Ne decide rien : lit le corps BRUT, l'en-tete et la cle publique,
 * puis delegue.
 *
 * <p>Le corps est recu en {@code String} et non en objet : il faut les octets exacts pour
 * recalculer le HMAC, et un aller-retour Jackson ne garantirait plus l'egalite. C'est aussi
 * ce qui permet de n'analyser le contenu qu'apres authentification.
 *
 * <p>La route est en {@code permitAll} dans {@code SecurityConfig} : ces requetes viennent
 * de serveurs tiers qui ne peuvent pas s'authentifier autrement. Leur authentification,
 * c'est la signature — ne pas y superposer un mecanisme Spring Security.
 */
@RestController
@RequestMapping("/api/webhooks/leads")
public class LeadWebhookController {

    private final LeadCaptureService service;

    public LeadWebhookController(LeadCaptureService service) {
        this.service = service;
    }

    @PostMapping(path = "/{clientKey}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CaptureAccepted capture(
            @PathVariable String clientKey,
            @RequestBody String corpsBrut,
            @RequestHeader(value = "${leadflow.webhook.signature-header}", required = false)
                    String signature) {
        return service.capture(clientKey, corpsBrut, signature);
    }
}
```

- [ ] **Step 6: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=LeadCaptureIntegrationTest
```

Attendu : 11 tests verts.

Si `refuseUnCorpsTropGros` échoue en `400` au lieu de `413`, vérifier que le contrôle de taille est **avant** la désérialisation dans `LeadCaptureService.capture`.

Si `repondLaMemeChoseQuelleQueSoitLaCauseDuRefus` échoue, comparer les deux corps : le `ProblemDetail` ne doit contenir aucune information issue de la requête.

- [ ] **Step 7: Lancer la suite complète et commiter**

```bash
./mvnw test
```

```bash
git add backend/src/main/java/com/leadflow/capture/LeadWebhookController.java \
        backend/src/main/java/com/leadflow/capture/LeadCaptureService.java \
        backend/src/main/java/com/leadflow/capture/CaptureAccepted.java \
        backend/src/main/java/com/leadflow/common/PayloadRejectedException.java \
        backend/src/main/java/com/leadflow/common/ApiExceptionHandler.java \
        backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java
git commit -m "feat: endpoint webhook signe, refus uniformes et evenement brut persiste"
```

---

## Task 4: Idempotence du rejeu

L'index de la tâche 1 refuse déjà le doublon en base ; il reste à en faire une réponse correcte plutôt qu'une `500`.

**Files:**
- Modify : `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java`
- Modify : `backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java`

**Interfaces:**
- Consumes : `RawLeadEventRepository.findByClientIdAndSignature(UUID, String)` (tâche 1).
- Produces : rien de nouveau — `capture(...)` rend le même `CaptureAccepted` pour un rejeu exact.

- [ ] **Step 1: Ajouter les deux tests de rejeu**

Ajouter dans `LeadCaptureIntegrationTest`, avant la dernière accolade :

```java
    @Test
    void rejoueLaMemeRequeteSansCreerDeSecondEvenement() throws Exception {
        String enTete = enTeteValide(SECRET, CORPS);

        String premiere = mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTete)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String seconde = mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTete)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(seconde).isEqualTo(premiere);
        assertThat(rawLeadEventRepository.findAll()).hasSize(1);
    }

    @Test
    void deuxSoumissionsDistinctesRestentDeuxEvenements() throws Exception {
        // Horodatages differents donc signatures differentes : ce n'est pas un rejeu, et
        // deux vraies soumissions ne doivent surtout pas etre confondues.
        long maintenant = Instant.now().getEpochSecond();

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", signe(SECRET, maintenant, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", signe(SECRET, maintenant - 1, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted());

        assertThat(rawLeadEventRepository.findAll()).hasSize(2);
    }
```

- [ ] **Step 2: Lancer les tests et vérifier qu'ils échouent**

```bash
./mvnw test -Dtest=LeadCaptureIntegrationTest#rejoueLaMemeRequeteSansCreerDeSecondEvenement
```

Attendu : échec — la seconde requête remonte une `DataIntegrityViolationException`, donc une `500`.

- [ ] **Step 3: Rendre l'identifiant existant au lieu d'insérer**

Dans `LeadCaptureService.capture`, insérer ce bloc **juste après** la vérification de signature et **avant** le contrôle de taille :

```java
        // Rejeu exact : meme client, meme en-tete signe. On rend l'identifiant deja attribue
        // sans republier — si la premiere publication avait echoue, la ligne est restee
        // RECEIVED et c'est le filet qui s'en charge, pas cette requete.
        Optional<RawLeadEvent> dejaVu =
                rawLeadEventRepository.findByClientIdAndSignature(client.getId(), enTeteSignature);
        if (dejaVu.isPresent()) {
            return new CaptureAccepted(dejaVu.get().getId());
        }
```

Ajouter l'import `java.util.Optional`.

- [ ] **Step 4: Lancer les tests et vérifier qu'ils passent**

```bash
./mvnw test -Dtest=LeadCaptureIntegrationTest
```

Attendu : 13 tests verts.

- [ ] **Step 5: Lancer la suite complète et commiter**

```bash
./mvnw test
```

```bash
git add backend/src/main/java/com/leadflow/capture/LeadCaptureService.java \
        backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java
git commit -m "feat: un rejeu exact rend l'identifiant deja attribue"
```

---

## Task 5: Publication après commit

La ligne existe ; il faut maintenant qu'elle parte sur la file, et jamais avant que la transaction soit validée.

**Files:**
- Create : `backend/src/main/java/com/leadflow/capture/LeadCapturedEvent.java`
- Create : `backend/src/main/java/com/leadflow/capture/CapturedLeadMessage.java`
- Create : `backend/src/main/java/com/leadflow/capture/LeadEventPublisher.java`
- Modify : `backend/src/main/java/com/leadflow/capture/LeadCaptureService.java`
- Modify : `backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java`

**Interfaces:**
- Consumes : `RabbitMQConfig.LEADS_EXCHANGE`, `RabbitMQConfig.LEADS_ROUTING_KEY`, `RabbitMQConfig.LEADS_QUEUE`, `RabbitTemplate`.
- Produces : `CapturedLeadMessage(UUID eventId, UUID clientId, String source, Instant receivedAt)` — le contrat de file de F3 ; `LeadCapturedEvent(UUID eventId, UUID clientId, String source, Instant receivedAt)` — l'événement Spring interne ; `LeadEventPublisher.publie(LeadCapturedEvent)`.

- [ ] **Step 1: Ajouter le test de publication**

Ajouter dans `LeadCaptureIntegrationTest` les champs et le test suivants :

```java
    @Autowired private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Test
    void publieLeMessageSurLaFileEtMarqueLEvenementPublie() throws Exception {
        // On vide la file d'abord : un test precedent a pu y laisser un message.
        while (rabbitTemplate.receive(com.leadflow.config.RabbitMQConfig.LEADS_QUEUE, 200) != null) {
            // rien : on purge
        }

        mockMvc.perform(post("/api/webhooks/leads/{cle}", clePublique)
                        .header("X-Leadflow-Signature", enTeteValide(SECRET, CORPS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPS))
                .andExpect(status().isAccepted());

        // Le message est reellement consomme depuis RabbitMQ, pas verifie sur un mock :
        // c'est la seule facon de prouver que la topologie et le convertisseur marchent.
        Object recu = rabbitTemplate.receiveAndConvert(
                com.leadflow.config.RabbitMQConfig.LEADS_QUEUE, 5000);

        assertThat(recu).isInstanceOf(CapturedLeadMessage.class);
        CapturedLeadMessage message = (CapturedLeadMessage) recu;
        RawLeadEvent evenement = rawLeadEventRepository.findAll().getFirst();
        assertThat(message.eventId()).isEqualTo(evenement.getId());
        assertThat(message.clientId()).isEqualTo(clientId);
        assertThat(message.source()).isEqualTo("formulaire-devis");

        RawLeadEvent relu = rawLeadEventRepository.findById(evenement.getId()).orElseThrow();
        assertThat(relu.getStatus()).isEqualTo(RawLeadEventStatus.PUBLISHED);
        assertThat(relu.getPublishedAt()).isNotNull();
    }
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=LeadCaptureIntegrationTest#publieLeMessageSurLaFileEtMarqueLEvenementPublie
```

Attendu : échec de compilation — `CapturedLeadMessage` n'existe pas.

- [ ] **Step 3: Écrire les deux records**

Créer `backend/src/main/java/com/leadflow/capture/CapturedLeadMessage.java` :

```java
package com.leadflow.capture;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de file publie sur {@code lead.captured}. C'est la frontiere publique de la
 * couche capture : F3 ne connaitra rien d'autre d'elle.
 *
 * <p>Une reference, pas un contenu. La base reste l'unique source de verite et le
 * consommateur relit {@code raw_lead_event.payload} : un rejeu depuis la DLQ travaille donc
 * forcement sur la donnee a jour, et le payload n'existe pas en double.
 *
 * <p><b>Le consommateur doit etre idempotent sur {@code eventId}.</b> Si une publication
 * reussit mais que le passage a PUBLISHED echoue, le filet de republication renverra le
 * message : la livraison est at-least-once, jamais exactly-once.
 */
public record CapturedLeadMessage(
        UUID eventId,
        UUID clientId,
        String source,
        Instant receivedAt) {
}
```

Créer `backend/src/main/java/com/leadflow/capture/LeadCapturedEvent.java` :

```java
package com.leadflow.capture;

import java.time.Instant;
import java.util.UUID;

/**
 * Evenement applicatif interne, publie dans la transaction de capture et consomme apres
 * son commit. Distinct de {@link CapturedLeadMessage} a dessein : l'un circule dans la JVM,
 * l'autre est un contrat inter-services qu'on ne veut pas faire bouger par accident.
 */
public record LeadCapturedEvent(
        UUID eventId,
        UUID clientId,
        String source,
        Instant receivedAt) {
}
```

- [ ] **Step 4: Écrire le publieur**

Créer `backend/src/main/java/com/leadflow/capture/LeadEventPublisher.java` :

```java
package com.leadflow.capture;

import com.leadflow.config.RabbitMQConfig;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publie apres le commit de la capture, jamais avant : un message parti trop tot
 * designerait une ligne que le consommateur ne trouverait pas.
 *
 * <p>{@code REQUIRES_NEW} parce qu'a ce moment la transaction de capture est deja commitee
 * et qu'il n'y en a plus aucune : le changement de statut a besoin de la sienne. Meme
 * raisonnement que {@code CrmSyncTraceWriter} en F5.
 *
 * <p>Les deux annotations sont sur la meme methode a dessein : le filet de republication
 * appelle {@link #publie} directement, et passe donc par le proxy comme le listener.
 */
@Component
public class LeadEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LeadEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RawLeadEventRepository rawLeadEventRepository;

    public LeadEventPublisher(
            RabbitTemplate rabbitTemplate, RawLeadEventRepository rawLeadEventRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.rawLeadEventRepository = rawLeadEventRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publie(LeadCapturedEvent evenement) {
        RawLeadEvent ligne = rawLeadEventRepository.findById(evenement.eventId()).orElse(null);
        if (ligne == null || ligne.getStatus() == RawLeadEventStatus.PUBLISHED) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LEADS_EXCHANGE,
                    RabbitMQConfig.LEADS_ROUTING_KEY,
                    new CapturedLeadMessage(
                            evenement.eventId(),
                            evenement.clientId(),
                            evenement.source(),
                            evenement.receivedAt()));
            ligne.setStatus(RawLeadEventStatus.PUBLISHED);
            ligne.setPublishedAt(Instant.now());
            ligne.setFailureReason(null);
        } catch (AmqpException echec) {
            // La ligne reste en base : c'est le filet qui la reprendra. Ne jamais relancer
            // ici, l'appelant HTTP a deja recu son 202 et la transaction est close.
            log.warn("Publication de l'evenement {} en echec", evenement.eventId(), echec);
            ligne.setStatus(RawLeadEventStatus.FAILED);
            ligne.setFailureReason(echec.getMessage());
        }
        rawLeadEventRepository.save(ligne);
    }
}
```

- [ ] **Step 5: Publier l'événement depuis le service**

Dans `LeadCaptureService`, ajouter le champ et l'appel.

Ajouter au constructeur le paramètre `ApplicationEventPublisher evenements` (import `org.springframework.context.ApplicationEventPublisher`), le champ correspondant, puis remplacer la fin de `capture` :

```java
        RawLeadEvent enregistre = rawLeadEventRepository.saveAndFlush(evenement);

        // Publie DANS la transaction, consomme APRES son commit : c'est tout le mecanisme
        // du @TransactionalEventListener(AFTER_COMMIT) cote publieur.
        evenements.publishEvent(new LeadCapturedEvent(
                enregistre.getId(),
                enregistre.getClientId(),
                enregistre.getSource(),
                enregistre.getReceivedAt()));

        return new CaptureAccepted(enregistre.getId());
```

- [ ] **Step 6: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=LeadCaptureIntegrationTest
```

Attendu : 14 tests verts.

Si `recu` est une `Map` plutôt qu'un `CapturedLeadMessage`, c'est que l'en-tête de type n'a pas été posé : vérifier que le `RabbitTemplate` injecté est bien celui de `RabbitMQConfig`, avec son `JacksonJsonMessageConverter`.

- [ ] **Step 7: Lancer la suite complète et commiter**

```bash
./mvnw test
```

```bash
git add backend/src/main/java/com/leadflow/capture/CapturedLeadMessage.java \
        backend/src/main/java/com/leadflow/capture/LeadCapturedEvent.java \
        backend/src/main/java/com/leadflow/capture/LeadEventPublisher.java \
        backend/src/main/java/com/leadflow/capture/LeadCaptureService.java \
        backend/src/test/java/com/leadflow/capture/LeadCaptureIntegrationTest.java
git commit -m "feat: publication sur la file apres commit de la capture"
```

---

## Task 6: Filet de republication

Sans lui, un broker indisponible perd le lead en silence — ce qui contredirait la garantie de non-perte annoncée par `CLAUDE.md`.

**Files:**
- Create : `backend/src/main/java/com/leadflow/capture/PendingEventRelay.java`
- Create : `backend/src/main/java/com/leadflow/config/SchedulingConfig.java`
- Create : `backend/src/test/java/com/leadflow/capture/PendingEventRelayTest.java`

**Interfaces:**
- Consumes : `LeadEventPublisher.publie(LeadCapturedEvent)`, `RawLeadEventRepository.findByStatusInAndReceivedAtBefore(...)`, `WebhookProperties.relayAfter()` et `relayInterval()`.
- Produces : `PendingEventRelay.republieLesEnAttente()` — appelable directement par un test, sans attendre l'ordonnanceur.

- [ ] **Step 1: Écrire le test du filet**

Créer `backend/src/test/java/com/leadflow/capture/PendingEventRelayTest.java` :

```java
package com.leadflow.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import com.leadflow.config.RabbitMQConfig;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * La methode est appelee directement plutot que d'attendre l'ordonnanceur : un test qui
 * dort pour laisser passer une periode est lent et instable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PendingEventRelayTest {

    @Autowired private PendingEventRelay relais;
    @Autowired private RawLeadEventRepository rawLeadEventRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RabbitTemplate rabbitTemplate;

    private UUID clientId;

    @BeforeEach
    void preparer() {
        Client client = new Client();
        client.setPublicKey("cle-" + UUID.randomUUID());
        client.setName("Boutique de test");
        client.setHmacSecret("secret-de-signature");
        client.setCrmProviderId("dolibarr");
        client.setCrmConfig(Map.of("baseUrl", "http://localhost:8081", "apiKey", "cle"));
        clientId = clientRepository.saveAndFlush(client).getId();

        while (rabbitTemplate.receive(RabbitMQConfig.LEADS_QUEUE, 200) != null) {
            // purge
        }
    }

    private RawLeadEvent evenement(RawLeadEventStatus statut, long ageSecondes) {
        RawLeadEvent evenement = new RawLeadEvent();
        evenement.setClientId(clientId);
        evenement.setSource("formulaire-devis");
        evenement.setPayload(Map.of("email", "karim@acme.test"));
        evenement.setSignature("t=" + Instant.now().getEpochSecond() + ",v1=" + UUID.randomUUID());
        evenement.setReceivedAt(Instant.now().minusSeconds(ageSecondes));
        evenement.setStatus(statut);
        return rawLeadEventRepository.saveAndFlush(evenement);
    }

    @Test
    void republieUnEvenementRecuMaisJamaisPublie() {
        RawLeadEvent oublie = evenement(RawLeadEventStatus.RECEIVED, 600);

        relais.republieLesEnAttente();

        Object recu = rabbitTemplate.receiveAndConvert(RabbitMQConfig.LEADS_QUEUE, 5000);
        assertThat(recu).isInstanceOf(CapturedLeadMessage.class);
        assertThat(((CapturedLeadMessage) recu).eventId()).isEqualTo(oublie.getId());
        assertThat(rawLeadEventRepository.findById(oublie.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.PUBLISHED);
    }

    @Test
    void republieUnEvenementEnEchec() {
        RawLeadEvent echoue = evenement(RawLeadEventStatus.FAILED, 600);

        relais.republieLesEnAttente();

        assertThat(rawLeadEventRepository.findById(echoue.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.PUBLISHED);
    }

    @Test
    void ignoreUnEvenementTropRecent() {
        // Le filet ne doit jamais doubler une publication en cours : relay-after est
        // volontairement plus long que la duree d'une publication normale.
        RawLeadEvent recent = evenement(RawLeadEventStatus.RECEIVED, 0);

        relais.republieLesEnAttente();

        assertThat(rawLeadEventRepository.findById(recent.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.RECEIVED);
        assertThat(rabbitTemplate.receive(RabbitMQConfig.LEADS_QUEUE, 500)).isNull();
    }

    @Test
    void ignoreUnEvenementDejaPublie() {
        RawLeadEvent publie = evenement(RawLeadEventStatus.PUBLISHED, 600);

        relais.republieLesEnAttente();

        assertThat(rabbitTemplate.receive(RabbitMQConfig.LEADS_QUEUE, 500)).isNull();
        assertThat(rawLeadEventRepository.findById(publie.getId()).orElseThrow().getStatus())
                .isEqualTo(RawLeadEventStatus.PUBLISHED);
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue**

```bash
./mvnw test -Dtest=PendingEventRelayTest
```

Attendu : échec de compilation — `PendingEventRelay` n'existe pas.

- [ ] **Step 3: Activer l'ordonnanceur**

Créer `backend/src/main/java/com/leadflow/config/SchedulingConfig.java` :

```java
package com.leadflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active l'ordonnanceur, dont le filet de republication de la capture a besoin. Isole dans
 * sa propre classe pour qu'un test qui voudrait s'en passer puisse l'exclure sans toucher
 * a la classe d'application.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

- [ ] **Step 4: Écrire le filet**

Créer `backend/src/main/java/com/leadflow/capture/PendingEventRelay.java` :

```java
package com.leadflow.capture;

import com.leadflow.config.WebhookProperties;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Filet, pas chemin normal. Reprend ce que la publication apres commit n'a pas reussi a
 * envoyer : broker indisponible, ou processus tue entre le commit et la publication.
 *
 * <p>Ne reprend que les lignes plus vieilles que {@code relay-after}, delai volontairement
 * plus long qu'une publication normale, pour ne jamais doubler un envoi en cours.
 *
 * <p><b>Mono-instance.</b> Deux instances balaieraient les memes lignes et publieraient
 * deux fois. Le projet est deploye en une seule instance ; le jour ou ca change, la reponse
 * est un {@code SELECT ... FOR UPDATE SKIP LOCKED}, pas un verrou applicatif.
 */
@Component
public class PendingEventRelay {

    private static final Logger log = LoggerFactory.getLogger(PendingEventRelay.class);

    private final RawLeadEventRepository rawLeadEventRepository;
    private final LeadEventPublisher publieur;
    private final WebhookProperties properties;

    public PendingEventRelay(
            RawLeadEventRepository rawLeadEventRepository,
            LeadEventPublisher publieur,
            WebhookProperties properties) {
        this.rawLeadEventRepository = rawLeadEventRepository;
        this.publieur = publieur;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${leadflow.webhook.relay-interval}")
    public void republieLesEnAttente() {
        Instant limite = Instant.now().minus(properties.relayAfter());
        List<RawLeadEvent> enAttente = rawLeadEventRepository.findByStatusInAndReceivedAtBefore(
                List.of(RawLeadEventStatus.RECEIVED, RawLeadEventStatus.FAILED), limite);

        if (enAttente.isEmpty()) {
            return;
        }
        log.info("Republication de {} evenement(s) restes non publies", enAttente.size());

        for (RawLeadEvent evenement : enAttente) {
            // Appel a travers le proxy : c'est ce qui donne au publieur sa transaction
            // REQUIRES_NEW, exactement comme lorsqu'il est declenche par le listener.
            publieur.publie(new LeadCapturedEvent(
                    evenement.getId(),
                    evenement.getClientId(),
                    evenement.getSource(),
                    evenement.getReceivedAt()));
        }
    }
}
```

- [ ] **Step 5: Lancer le test et vérifier qu'il passe**

```bash
./mvnw test -Dtest=PendingEventRelayTest
```

Attendu : 4 tests verts.

Si `ignoreUnEvenementTropRecent` échoue, vérifier que `relay-after` vaut bien `2m` dans `application.yml` : un délai plus court rendrait le test juste mais le filet dangereux.

- [ ] **Step 6: Lancer la suite complète et commiter**

```bash
./mvnw test
```

```bash
git add backend/src/main/java/com/leadflow/capture/PendingEventRelay.java \
        backend/src/main/java/com/leadflow/config/SchedulingConfig.java \
        backend/src/test/java/com/leadflow/capture/PendingEventRelayTest.java
git commit -m "feat: filet de republication des evenements non publies"
```

---

## Task 7: Documentation d'intégration et recette

`CLAUDE.md` est la mémoire du projet entre deux sessions, et la doc d'intégration est ce qu'un client recevra. C'est le critère de recette n°7 et n°8.

**Files:**
- Create : `docs/webhook-integration.md`
- Modify : `CLAUDE.md`

**Interfaces:**
- Consumes : tout ce qui précède.
- Produces : rien de logiciel.

- [ ] **Step 1: Écrire la documentation d'intégration**

Créer `docs/webhook-integration.md` :

````markdown
# Intégrer un formulaire à LeadFlow

Ce document décrit le contrat du webhook de capture. Il est la référence pour le snippet
posé sur le site d'un client.

## Endpoint

```
POST /api/webhooks/leads/{clePublique}
Content-Type: application/json
X-Leadflow-Signature: t=<epoch secondes>,v1=<hmac hexadecimal>
```

`clePublique` est la valeur de `client.public_key`. Elle n'est pas secrète : c'est la
signature qui authentifie. Elle peut être révoquée sans recréer le client.

## Corps

Un objet JSON libre, à une exception près : le champ `source` est obligatoire et indique le
canal d'origine chez le client.

```json
{
  "source": "formulaire-devis",
  "email": "karim@acme.test",
  "telephone": "+212600000000",
  "message": "Je veux un devis pour 50 unites"
}
```

Tout le reste est stocké tel quel et interprété plus tard par la qualification. Le
middleware ne valide ni l'email, ni le téléphone.

## Signature

```
charge   = <epoch secondes> + "." + <corps JSON exact, octet pour octet>
signature = HMAC-SHA256(secret du client, charge)  ->  hexadecimal minuscule
en-tete  = "t=" + <epoch secondes> + ",v1=" + signature
```

Le corps signé doit être **exactement** celui envoyé : un espace ajouté après signature
invalide la requête.

L'horodatage doit être à moins de 5 minutes de l'heure du serveur, dans les deux sens.

### En PHP

```php
$corps = json_encode([
    'source' => 'formulaire-devis',
    'email'  => 'karim@acme.test',
]);
$t = time();
$signature = hash_hmac('sha256', $t . '.' . $corps, $secret);

$ch = curl_init('https://leadflow.example/api/webhooks/leads/' . $clePublique);
curl_setopt_array($ch, [
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => $corps,
    CURLOPT_HTTPHEADER => [
        'Content-Type: application/json',
        "X-Leadflow-Signature: t=$t,v1=$signature",
    ],
]);
curl_exec($ch);
```

### En JavaScript (Node)

```js
import crypto from 'node:crypto';

const corps = JSON.stringify({ source: 'formulaire-devis', email: 'karim@acme.test' });
const t = Math.floor(Date.now() / 1000);
const signature = crypto.createHmac('sha256', secret).update(`${t}.${corps}`).digest('hex');

await fetch(`https://leadflow.example/api/webhooks/leads/${clePublique}`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Leadflow-Signature': `t=${t},v1=${signature}`,
  },
  body: corps,
});
```

Le secret ne doit jamais partir dans un navigateur : la signature se calcule côté serveur.

### Vérifier en local avec curl

Backend démarré sur `:8080` et données de démonstration chargées (profil `dev`). Les
valeurs ci-dessous sont celles du client de démonstration de
`backend/src/main/resources/db/dev/R__demo_data.sql`, dont le secret HMAC est donné en clair
en commentaire de ce fichier :

```bash
CLE="demo-cd253966049ebd76243248e8"
SECRET="c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19"
CORPS='{"source":"formulaire-devis","email":"karim@acme.test"}'
T=$(date +%s)
SIG=$(printf '%s' "$T.$CORPS" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

curl -i -X POST "http://localhost:8080/api/webhooks/leads/$CLE" \
  -H "Content-Type: application/json" \
  -H "X-Leadflow-Signature: t=$T,v1=$SIG" \
  -d "$CORPS"
```

Attendu : `202 Accepted` et `{"eventId":"..."}`.

## Réponses

| Code | Signification |
| --- | --- |
| `202` | Accepté. Le corps porte `eventId`. |
| `401` | Authentification refusée. La cause exacte n'est pas divulguée. |
| `400` | Corps illisible, ou champ `source` absent. |
| `413` | Corps au-delà de 64 Ko. |

## Rejeu

Renvoyer **exactement** la même requête (même corps, même en-tête) ne crée pas de second
lead : le service rend le `eventId` déjà attribué avec un `202`. Un client peut donc
réessayer sans risque après un timeout réseau.
````

- [ ] **Step 2: Mettre à jour `CLAUDE.md`**

Dans la section « Backend — conventions », après le paragraphe sur les deux étages de test
des adaptateurs ERP, ajouter :

```markdown
### Capture — le contrat d'entree

Le webhook est `POST /api/webhooks/leads/{clePublique}`, authentifie par l'en-tete
`X-Leadflow-Signature: t=<epoch>,v1=<hex>` ou l'hexadecimal est
`HMAC-SHA256(client.hmac_secret, t + "." + corps brut)`. Le contrat complet, avec exemples
PHP, JS et curl, vit dans `docs/webhook-integration.md`.

**Le corps n'est jamais deserialise avant authentification.** Le controleur le recoit en
`String` : il faut les octets exacts pour recalculer le HMAC, et faire tourner Jackson sur
une entree non authentifiee reviendrait a traiter une donnee dont on n'a pas verifie
l'origine. Un test le verrouille — corps JSON invalide plus signature invalide doit rendre
`401`, jamais `400`.

**Les cinq causes de refus rendent la meme reponse `401`** : cle publique inconnue, client
desactive, en-tete absent, signature fausse, horodatage hors fenetre. Distinguer les codes
donnerait un oracle sur les cles publiques existantes. Le detail n'existe que dans les logs.

**L'idempotence est en base**, par l'index unique `(client_id, signature)` de `V3` : un
rejeu exact rend le `eventId` deja attribue. Une verification applicative ne suffirait pas,
deux requetes concurrentes la passeraient toutes les deux.

**La publication est at-least-once.** Elle part apres le commit
(`@TransactionalEventListener(AFTER_COMMIT)`), et `PendingEventRelay` reprend
periodiquement ce qui est reste non publie. Si l'envoi reussit mais que le passage a
`PUBLISHED` echoue, le message est renvoye : **le consommateur de F3 doit etre idempotent
sur `eventId`**. Le filet est mono-instance ; deux instances demanderaient un
`SELECT ... FOR UPDATE SKIP LOCKED`.
```

- [ ] **Step 3: Mettre à jour la section « Etat actuel » de `CLAUDE.md`**

Remplacer la section par :

```markdown
## Etat actuel

Le modele de donnees est complet (F1), les deux adaptateurs ERP existent (F5), et
**l'entree du pipeline est ouverte** (F2).

Ce qui existe : la configuration, le chiffrement des secrets, les cinq entites et leurs
repositories, les migrations `V1` a `V3`, le port `CrmConnector` et son registre, les
adaptateurs Dolibarr et Odoo, `CrmSyncService`, et la couche `capture` complete — webhook
signe, evenement brut persiste, publication sur RabbitMQ avec filet de republication.

Ce qui n'existe pas : **le milieu du pipeline**. Personne ne consomme
`leadflow.leads.captured` : pas de qualification (F3), pas de routage (F4), pas d'API de
monitoring (F6) ; les quatre composants de `features/` sont des placeholders. Rien
n'appelle donc encore `CrmSyncService` en dehors des tests. Ne pas supposer l'existence
d'un service ou d'un endpoint : verifier avant de referencer.
```

- [ ] **Step 4: Recette complète**

```bash
./mvnw verify
```

Attendu : `BUILD SUCCESS`, avec `HmacSignatureVerifierTest`, `LeadCaptureIntegrationTest`,
`RawLeadEventIdempotenceTest` et `PendingEventRelayTest` présents et verts.

Vérifier ensuite les critères que nul test n'automatise :

```bash
# Le corps brut ne doit jamais etre journalise, et le secret non plus.
grep -rn "corpsBrut\|hmacSecret\|getHmacSecret" backend/src/main/java/com/leadflow/ | grep -i "log"
```

Attendu : **aucun résultat**.

Puis la recette manuelle du critère n°5 — non-perte quand le broker est absent :

```bash
docker compose stop rabbitmq
# lancer le backend, envoyer une requete signee : elle doit repondre 202
docker compose start rabbitmq
# attendre relay-after + relay-interval, puis verifier :
docker exec leadflow-postgres psql -U leadflow -d leadflow \
  -c "SELECT status, published_at FROM raw_lead_event ORDER BY received_at DESC LIMIT 1;"
```

Attendu : `PUBLISHED` avec un `published_at` renseigné.

- [ ] **Step 5: Commit**

```bash
git add docs/webhook-integration.md CLAUDE.md
git commit -m "docs: contrat du webhook et etat du pipeline apres F2"
```

---

## Après la dernière tâche

Utiliser la compétence `superpowers:requesting-code-review` pour une revue de branche, traiter ce qui doit l'être, puis `superpowers:finishing-a-development-branch` pour fusionner `feature/f2-capture-securisee` dans `main`.

**Ne pas supprimer la branche après la fusion** : les branches de feature sont conservées comme historique du projet.
